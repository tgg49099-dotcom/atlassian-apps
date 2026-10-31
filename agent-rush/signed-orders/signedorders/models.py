"""The vocabulary of the control plane.

Nothing in this module touches the network, the clock, the disk or a model: it
is pure data.  That is what makes it impossible for the policy, the journal
and the UI to disagree about what an *order* is.

The three types to read first:

``Order``      what the agent asks for (immutable).
``Trace``      why the control plane decided what it decided (rule, input, output).
``Decision``  the verdict plus its trace plus the signature when there is one.
"""

from __future__ import annotations

import enum
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

__all__ = [
    "Action",
    "Approval",
    "Decision",
    "Order",
    "Risk",
    "Trace",
    "Verdict",
    "utcnow",
]


def utcnow() -> datetime:
    """The current UTC time, timezone-aware."""
    return datetime.now(timezone.utc)


class Action(enum.StrEnum):
    """What an order asks the executor to do.

    The set is closed on purpose: an unknown action is a *high* risk order
    that waits for a human, never an accident.
    """

    READ = "read"          # read a file of the sandbox
    WRITE = "write"        # write a file of the sandbox
    TEST = "test"          # run the test suite of the sandbox
    EXEC = "exec"          # run a command in the sandbox
    APPLY = "apply"        # apply a patch to the target service
    RESTART = "restart"    # restart the target service
    DELETE = "delete"      # delete something
    NETWORK = "network"    # outbound network call
    ESCALATE = "escalate"  # ask a human a free-text question


class Risk(enum.StrEnum):
    """How dangerous an order is. The policy is the only thing that sets it."""

    LOW = "low"            # executed automatically
    MEDIUM = "medium"      # executed automatically, but mutating: snapshot + rollback
    HIGH = "high"          # queued for a human; expires if nobody answers
    FORBIDDEN = "forbidden"  # refused by the policy: no human override exists


class Verdict(enum.StrEnum):
    """What the control plane decided about an order."""

    AUTO_EXECUTE = "auto_execute"        # signed, ready to run
    QUEUED_FOR_HUMAN = "queued_for_human"  # waiting for a signed human approval
    REFUSED = "refused"                  # forbidden, or a quota broke
    APPROVED = "approved"                # a human signed the approval
    REJECTED = "rejected"                # a human refused it
    EXPIRED = "expired"                  # nobody answered in time
    EXECUTED = "executed"                # it ran, in a fresh sandbox
    FAILED = "failed"                    # it ran and failed


@dataclass(frozen=True)
class Order:
    """An immutable request from the agent to do something.

    The agent fills this in; the control plane never mutates it.  Everything
    that happens afterwards is recorded in the journal, not in the order.
    """

    id: str
    task_id: str
    agent_id: str
    action: Action
    target: str
    args: dict[str, Any] = field(default_factory=dict)
    justification: str = ""
    created_at: datetime = field(default_factory=utcnow)

    @classmethod
    def new(
        cls,
        *,
        task_id: str,
        agent_id: str,
        action: Action,
        target: str,
        args: dict[str, Any] | None = None,
        justification: str = "",
        order_id: str | None = None,
    ) -> "Order":
        """Build an order with a fresh identifier."""
        return cls(
            id=order_id or f"so_{uuid.uuid4().hex[:16]}",
            task_id=task_id,
            agent_id=agent_id,
            action=action,
            target=target,
            args=dict(args or {}),
            justification=justification,
        )

    def canonical(self) -> dict[str, Any]:
        """The exact mapping that is hashed and signed.

        ``created_at`` is normalised to UTC ISO-8601 with a trailing ``Z`` so
        that the same order always produces the same signature.
        """
        action = (
            self.action.value
            if isinstance(self.action, Action)
            else str(self.action)
        )
        return {
            "id": self.id,
            "task_id": self.task_id,
            "agent_id": self.agent_id,
            "action": action,
            "target": self.target,
            "args": self.args,
            "justification": self.justification,
            "created_at": self.created_at.astimezone(timezone.utc)
            .isoformat()
            .replace("+00:00", "Z"),
        }


@dataclass(frozen=True)
class Trace:
    """The audit trail of **one** decision.

    The mother asked for three things and this type holds exactly them:

    * the **rule** that was triggered (``rule_id`` + a one-sentence explanation);
    * the **input** the rule was given (``inputs``);
    * the **output** the rule produced (``outputs``).

    ``consulted`` lists the fields the rule actually read, and ``evaluated``
    lists the rules that were considered before the match, in order: it
    answers "why did *that* rule win?" without a debugger.
    """

    rule_id: str
    explanation: str
    inputs: dict[str, Any]
    outputs: dict[str, Any]
    evaluated: tuple[str, ...] = ()
    consulted: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return {
            "rule_id": self.rule_id,
            "explanation": self.explanation,
            "inputs": self.inputs,
            "outputs": self.outputs,
            "evaluated": list(self.evaluated),
            "consulted": list(self.consulted),
        }


@dataclass(frozen=True)
class Decision:
    """The control plane's answer to an order, and why."""

    order: Order
    risk: Risk
    verdict: Verdict
    rule_id: str
    reason: str
    trace: Trace
    signature: str | None = None
    public_key: str | None = None
    quota_snapshot: dict[str, Any] = field(default_factory=dict)
    decided_at: datetime = field(default_factory=utcnow)

    @property
    def executable(self) -> bool:
        """True when the order may run: it is signed, or approved by a human."""
        return self.verdict in (Verdict.AUTO_EXECUTE, Verdict.APPROVED)

    def canonical(self) -> dict[str, Any]:
        """The mapping that is signed when the order is authorised.

        It carries the order, the risk, the rule that decided it and the
        quota counters it was authorised under: everything a third party needs
        to check the decision without trusting the control plane.
        """
        payload = self.order.canonical()
        payload["risk"] = self.risk.value
        payload["rule_id"] = self.rule_id
        payload["quota_snapshot"] = dict(self.quota_snapshot)
        return payload

    def to_dict(self) -> dict[str, Any]:
        return {
            "order": self.order.canonical(),
            "risk": self.risk.value,
            "verdict": self.verdict.value,
            "rule_id": self.rule_id,
            "reason": self.reason,
            "trace": self.trace.to_dict(),
            "quota_snapshot": dict(self.quota_snapshot),
            "signature": self.signature,
            "public_key": self.public_key,
            "decided_at": self.decided_at.astimezone(timezone.utc)
            .isoformat()
            .replace("+00:00", "Z"),
        }


@dataclass(frozen=True)
class Approval:
    """A **signed** human approval of a queued order.

    The approval is signed with the human's own key, so "a human said yes" is
    an artefact that can be verified like any other — not a boolean in a
    database that anybody with a SQL editor can flip.
    """

    order_id: str
    approver: str
    decision: str          # "approve" | "reject"
    reason: str
    signature: str
    public_key: str
    created_at: datetime = field(default_factory=utcnow)

    def canonical(self) -> dict[str, Any]:
        return {
            "order_id": self.order_id,
            "approver": self.approver,
            "decision": self.decision,
            "reason": self.reason,
            "created_at": self.created_at.astimezone(timezone.utc)
            .isoformat()
            .replace("+00:00", "Z"),
        }
