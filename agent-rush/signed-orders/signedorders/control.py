"""The control plane — the only door between an agent and the world.

    submit(order)
        -> policy.classify(order)      (auditable rules, no model)
        -> quotas.check(...)           (sliding window, injectable clock)
        -> sign (Ed25519)  |  queue for a human  |  refuse
        -> journal

    approve(order_id, approver)       a human signs the approval
    execute(decision)                  only a signed order runs, and only in a
                                       fresh sandbox

The agent never signs, never executes and never decides its own risk level.
It can only *ask*.
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Sequence

from .canonical import canonical_json
from .journal import Journal
from .models import Action, Approval, Decision, Order, Risk, Trace, Verdict
from .policy import Policy, default_policy
from .quotas import QuotaManager
from .sandbox import SandboxBackend, SandboxResult, default_manifest
from .signer import SignedEnvelope, Signer, verify

__all__ = [
    "ControlPlane",
    "ExecutionRefused",
    "PendingOrder",
]


class ExecutionRefused(RuntimeError):
    """Raised when something tries to execute an order that may not run."""


@dataclass
class PendingOrder:
    """An order waiting for a human."""

    order: Order
    decision: Decision
    queued_at: float


@dataclass
class ControlPlane:
    """Signs, queues, refuses, executes — and journals every step."""

    journal: Journal
    signer: Signer
    quotas: QuotaManager = field(default_factory=QuotaManager)
    policy: Policy = field(default_factory=default_policy)
    clock: Callable[[], float] = time.time
    approval_ttl_seconds: float = 900.0
    pending: dict[str, PendingOrder] = field(default_factory=dict)
    approvals: dict[str, Approval] = field(default_factory=dict)

    # -- the agent-facing door --------------------------------------------- #

    def submit(self, order: Order) -> Decision:
        """The only thing the agent is allowed to do.

        Returns the decision.  The order is **not** executed here.
        """
        trace = self.policy.evaluate(order)

        # Every request counts against the agent's window, even the ones that
        # are refused: a hostile agent that spams must be throttled too.
        verdict = self.quotas.check(order.agent_id, order.task_id)

        risk = Risk(trace.outputs["risk"])
        if risk is Risk.FORBIDDEN:
            decision = Decision(
                order=order,
                risk=risk,
                verdict=Verdict.REFUSED,
                rule_id=trace.rule_id,
                reason=trace.explanation,
                trace=trace,
                quota_snapshot=verdict.snapshot,
            )
        elif not verdict.allowed:
            reason = (
                "quota exceeded: "
                + ", ".join(verdict.exceeded)
                + " (the order was refused, no human approval can unlock a quota)"
            )
            decision = Decision(
                order=order,
                risk=risk,
                verdict=Verdict.REFUSED,
                rule_id="quota.exceeded",
                reason=reason,
                trace=trace,
                quota_snapshot=verdict.snapshot,
            )
        elif risk is Risk.HIGH:
            decision = Decision(
                order=order,
                risk=risk,
                verdict=Verdict.QUEUED_FOR_HUMAN,
                rule_id=trace.rule_id,
                reason=trace.explanation,
                trace=trace,
                quota_snapshot=verdict.snapshot,
            )
        else:
            payload = decision_payload(
                order, risk, trace.rule_id, verdict.snapshot
            )
            envelope = self.signer.sign(payload)
            decision = Decision(
                order=order,
                risk=risk,
                verdict=Verdict.AUTO_EXECUTE,
                rule_id=trace.rule_id,
                reason=trace.explanation,
                trace=trace,
                signature=envelope.signature,
                public_key=envelope.public_key,
                quota_snapshot=verdict.snapshot,
            )

        self.quotas.charge_order(order.agent_id, order.task_id)
        self._journal_decision(decision, verdict.snapshot)
        if decision.verdict is Verdict.QUEUED_FOR_HUMAN:
            self.pending[order.id] = PendingOrder(
                order=order, decision=decision, queued_at=self.clock()
            )
        return decision

    # -- the human-facing door --------------------------------------------- #

    def approve(
        self,
        order_id: str,
        approver: str,
        reason: str = "",
        approval_signer: Signer | None = None,
    ) -> Decision | None:
        """A human approves a queued order, **with a signature**.

        The approval is signed with the human's own key, so "a human said
        yes" is an artefact that can be verified offline, not a boolean in a
        table that anybody with a SQL editor can flip.
        """
        pending = self.pending.get(order_id)
        if pending is None:
            return None
        signer = approval_signer or self.signer
        approval = self._make_approval(pending.order, approver, "approve", reason, signer)
        self.approvals[order_id] = approval
        self.journal.append(
            {
                "kind": "approval",
                "task_id": pending.order.task_id,
                "order_id": order_id,
                "approver": approver,
                "decision": "approve",
                "reason": reason,
                "signature": approval.signature,
                "public_key": approval.public_key,
                "at": _iso(self.clock()),
            }
        )
        decision = Decision(
            order=pending.order,
            risk=pending.decision.risk,
            verdict=Verdict.APPROVED,
            rule_id=pending.decision.rule_id,
            reason=f"approved by {approver}: {reason}" if reason else f"approved by {approver}",
            trace=pending.decision.trace,
        )
        self.pending.pop(order_id, None)
        self._journal_decision(decision, self.quotas.snapshot(
            pending.order.agent_id, pending.order.task_id))
        return decision

    def reject(self, order_id: str, approver: str, reason: str = "") -> Decision | None:
        """A human refuses a queued order."""
        pending = self.pending.get(order_id)
        if pending is None:
            return None
        approval = self._make_approval(
            pending.order, approver, "reject", reason, self.signer
        )
        self.approvals[order_id] = approval
        self.journal.append(
            {
                "kind": "approval",
                "task_id": pending.order.task_id,
                "order_id": order_id,
                "approver": approver,
                "decision": "reject",
                "reason": reason,
                "signature": approval.signature,
                "public_key": approval.public_key,
                "at": _iso(self.clock()),
            }
        )
        decision = Decision(
            order=pending.order,
            risk=pending.decision.risk,
            verdict=Verdict.REJECTED,
            rule_id=pending.decision.rule_id,
            reason=f"rejected by {approver}: {reason}" if reason else f"rejected by {approver}",
            trace=pending.decision.trace,
        )
        self.pending.pop(order_id, None)
        self._journal_decision(decision, self.quotas.snapshot(
            pending.order.agent_id, pending.order.task_id))
        return decision

    def expire(self, now: float | None = None) -> list[Decision]:
        """Expire the queued orders that nobody answered in time.

        No answer is **not** an approval: an order that nobody looks at dies.
        """
        moment = self.clock() if now is None else now
        expired: list[Decision] = []
        for order_id, pending in list(self.pending.items()):
            if moment - pending.queued_at >= self.approval_ttl_seconds:
                decision = Decision(
                    order=pending.order,
                    risk=pending.decision.risk,
                    verdict=Verdict.EXPIRED,
                    rule_id=pending.decision.rule_id,
                    reason=(
                        f"no human answered within {self.approval_ttl_seconds:.0f}s: "
                        "the order expired (silence is not an approval)"
                    ),
                    trace=pending.decision.trace,
                )
                self.pending.pop(order_id, None)
                self._journal_decision(decision, self.quotas.snapshot(
                    pending.order.agent_id, pending.order.task_id))
                expired.append(decision)
        return expired

    # -- the executor-facing door ------------------------------------------ #

    def check_executable(self, decision: Decision) -> None:
        """Raise :class:`ExecutionRefused` when the order may not run.

        Three cases are refused:

        * the order was refused by the policy or by a quota;
        * the order is still waiting for a human;
        * the signature (or the human approval) does not verify.
        """
        if decision.verdict is Verdict.REFUSED:
            raise ExecutionRefused(
                f"order {decision.order.id} was refused: {decision.reason}"
            )
        if decision.verdict is Verdict.QUEUED_FOR_HUMAN:
            raise ExecutionRefused(
                f"order {decision.order.id} is waiting for a human approval"
            )
        if decision.verdict is Verdict.AUTO_EXECUTE:
            envelope = SignedEnvelope(
                algorithm="ed25519",
                public_key=decision.public_key or "",
                signature=decision.signature or "",
                key_id="",
            )
            payload = decision_payload(
                decision.order,
                decision.risk,
                decision.rule_id,
                decision.quota_snapshot,
            )
            if not verify(envelope, payload):
                raise ExecutionRefused(
                    f"order {decision.order.id} carries an invalid signature"
                )
            return
        if decision.verdict is Verdict.APPROVED:
            approval = self.approvals.get(decision.order.id)
            if approval is None:
                raise ExecutionRefused(
                    f"order {decision.order.id} has no signed approval"
                )
            if not self.verify_approval(approval):
                raise ExecutionRefused(
                    f"the approval of order {decision.order.id} does not verify"
                )
            if approval.decision != "approve":
                raise ExecutionRefused(
                    f"order {decision.order.id} was not approved by the human"
                )
            return
        raise ExecutionRefused(
            f"order {decision.order.id} cannot be executed in state "
            f"{decision.verdict.value}"
        )

    def verify_approval(self, approval: Approval) -> bool:
        """Verify the signature of a human approval."""
        envelope = SignedEnvelope(
            algorithm="ed25519",
            public_key=approval.public_key,
            signature=approval.signature,
            key_id="",
        )
        return verify(envelope, approval.canonical())

    def execute(
        self,
        decision: Decision,
        backend: SandboxBackend,
        workspace: str | Path,
        sandbox_id: str | None = None,
    ) -> SandboxResult:
        """Run an authorised order in a fresh sandbox and journal the result."""
        self.check_executable(decision)
        order = decision.order
        workdir = Path(workspace)
        workdir.mkdir(parents=True, exist_ok=True)
        identifier = sandbox_id or f"sb-{uuid.uuid4().hex[:12]}"

        command = order.args.get("argv") or (
            [order.args["command"]] if isinstance(order.args.get("command"), str) else []
        )
        if not command:
            raise ExecutionRefused(f"order {order.id} carries no command to run")

        self.quotas.open_sandbox(order.task_id)
        try:
            result = backend.run(
                default_manifest(), command, workdir, identifier
            )
        finally:
            self.quotas.close_sandbox(order.task_id)

        self.quotas.charge_usage(
            order.task_id,
            cpu_seconds=int(result.duration_seconds),
        )
        self.journal.append(
            {
                "kind": "execution",
                "task_id": order.task_id,
                "order_id": order.id,
                "backend": getattr(backend, "name", "unknown"),
                "sandbox_id": result.sandbox_id or identifier,
                "exit_code": result.exit_code,
                "duration_seconds": round(result.duration_seconds, 3),
                "stdout": result.stdout[-4000:],
                "stderr": result.stderr[-4000:],
                "at": _iso(self.clock()),
            }
        )
        return result

    # -- helpers ----------------------------------------------------------- #

    def _make_approval(
        self,
        order: Order,
        approver: str,
        decision: str,
        reason: str,
        signer: Signer,
    ) -> Approval:
        from .models import utcnow

        created = utcnow()
        approval = Approval(
            order_id=order.id,
            approver=approver,
            decision=decision,
            reason=reason,
            signature="",
            public_key=signer.public_key_b64,
            created_at=created,
        )
        envelope = signer.sign(approval.canonical())
        return Approval(
            order_id=approval.order_id,
            approver=approval.approver,
            decision=approval.decision,
            reason=approval.reason,
            signature=envelope.signature,
            public_key=envelope.public_key,
            created_at=approval.created_at,
        )

    def _journal_decision(self, decision: Decision, snapshot: dict[str, Any]) -> None:
        self.journal.append(
            {
                "kind": "decision",
                "task_id": decision.order.task_id,
                "order": decision.order.canonical(),
                "risk": decision.risk.value,
                "verdict": decision.verdict.value,
                "rule_id": decision.rule_id,
                "reason": decision.reason,
                "trace": decision.trace.to_dict(),
                "quota_snapshot": snapshot,
                "signature": decision.signature,
                "public_key": decision.public_key,
                "at": _iso(self.clock()),
            }
        )


def decision_payload(
    order: Order,
    risk: Risk,
    rule_id: str,
    quota_snapshot: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """The exact mapping that is signed when an order is authorised.

    It is the order itself, plus the risk, plus the rule that decided it and
    the quota counters it was authorised under.  Everything a third party
    needs to check the decision — and nothing else.
    """
    payload = order.canonical()
    payload["risk"] = risk.value
    payload["rule_id"] = rule_id
    payload["quota_snapshot"] = dict(quota_snapshot or {})
    return payload


def _iso(moment: float) -> str:
    from datetime import datetime, timezone

    return datetime.fromtimestamp(moment, tz=timezone.utc).isoformat().replace(
        "+00:00", "Z"
    )
