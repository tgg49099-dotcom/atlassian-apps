"""The order lifecycle — the heart of the control plane.

    request -> identify -> classify -> quotas -> sign | queue for a human
            -> execute in a fresh sandbox -> journal -> (rollback on failure)

The agent can only call :meth:`OrderService.request`. It never signs, never
executes, and never decides its own risk level.

Two refusals, two different meanings:

* ``queued_for_human`` — a human may approve it (and only a human);
* ``refused``         — the policy forbids it; **no human override exists**.

TODO(build-window): the SQLite persistence, the expiry of queued orders, and
the compensating action that triggers the automatic rollback.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Any

from .journal import Journal
from .models import Action, Decision, Order, Risk, Verdict
from .policy import classify
from .quotas import QuotaLedger, QuotaLimits
from .signer import Signer


@dataclass
class OrderService:
    """Decides what happens to an order."""

    journal: Journal
    signer: Signer
    limits: QuotaLimits = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        if self.limits is None:
            self.limits = QuotaLimits()
        self.ledgers: dict[str, QuotaLedger] = {}

    # -- the agent-facing entry point --------------------------------------- #

    def request(self, order: Order) -> Decision:
        """The only thing the agent is allowed to do.

        Returns the decision; the order is never executed here.
        """
        risk, rule, reason = classify(order)
        ledger = self.ledgers.setdefault(order.task_id, QuotaLedger(order.task_id, self.limits))

        if risk is Risk.FORBIDDEN:
            decision = Decision(order, risk, Verdict.REFUSED, rule, reason)
        else:
            broken = ledger.exceeded()
            if broken:
                decision = Decision(
                    order, risk, Verdict.REFUSED, "quota.exceeded",
                    "quota exceeded: " + ", ".join(broken),
                )
            elif risk is Risk.HIGH:
                decision = Decision(order, risk, Verdict.QUEUED_FOR_HUMAN, rule, reason)
            else:
                envelope = self.signer.sign(order.canonical())
                decision = Decision(
                    order, risk, Verdict.AUTO_EXECUTE, rule, reason,
                    signature=envelope.signature,
                    signature_pubkey=envelope.public_key,
                )

        self.journal.append({
            "kind": "decision",
            "task_id": order.task_id,
            "order": order.canonical(),
            "risk": decision.risk.value,
            "verdict": decision.verdict.value,
            "rule": decision.rule,
            "reason": decision.reason,
            "signature": decision.signature,
            "public_key": decision.signature_pubkey,
        })
        return decision

    # -- the human-facing entry point -------------------------------------- #

    def approve(self, order_id: str, approver: str) -> Decision | None:
        """A human approves a queued order. Only high-risk orders wait."""
        raise NotImplementedError("TODO(build-window)")

    def refuse(self, order_id: str, approver: str, reason: str) -> Decision | None:
        """A human refuses a queued order."""
        raise NotImplementedError("TODO(build-window)")

    # -- the executor-facing entry point ----------------------------------- #

    def execute(self, decision: Decision) -> Any:
        """Run an approved order in a fresh sandbox and journal the result."""
        raise NotImplementedError("TODO(build-window)")

    # -- helpers ----------------------------------------------------------- #

    @staticmethod
    def new_order(task_id: str, agent_id: str, action: Action, target: str,
                  args: dict[str, Any] | None = None,
                  justification: str = "") -> Order:
        """Build an order with a random identifier."""
        return Order(
            id=f"so_{uuid.uuid4().hex[:16]}",
            task_id=task_id,
            agent_id=agent_id,
            action=action,
            target=target,
            args=args or {},
            justification=justification,
        )


__all__ = ["OrderService"]
