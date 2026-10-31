"""Core data types: the vocabulary shared by every component.

Nothing in this module talks to the network, the database or the model — it is
pure data so that the policy, the journal and the UI can never disagree about
what an order *is*.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

# --------------------------------------------------------------------------- #
# Risk
# --------------------------------------------------------------------------- #


class Risk(enum.StrEnum):
    """How dangerous an order is. The policy maps actions to these levels."""

    LOW = "low"        # executed automatically
    MEDIUM = "medium"  # executed automatically, but mutating: snapshot + rollback
    HIGH = "high"      # queued for a human, expires if nobody answers
    FORBIDDEN = "forbidden"  # refused by the policy, no human override possible


class Action(enum.StrEnum):
    """What an order asks the executor to do."""

    READ = "read"          # read a file inside the sandbox
    WRITE = "write"        # write a file inside the sandbox
    EXEC = "exec"          # run a command inside the sandbox
    APPLY = "apply"        # apply a patch to the target service
    RESTART = "restart"    # restart the target service
    NETWORK = "network"    # outbound network call
    ESCALATE = "escalate"  # ask a human (free-text question)


class Verdict(enum.StrEnum):
    """What the control plane decided about an order."""

    AUTO_EXECUTE = "auto_execute"
    QUEUED_FOR_HUMAN = "queued_for_human"
    REFUSED = "refused"
    EXPIRED = "expired"
    EXECUTED = "executed"
    FAILED = "failed"
    ROLLED_BACK = "rolled_back"


# --------------------------------------------------------------------------- #
# Orders
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class Order:
    """An immutable request from the agent to do something.

    The agent fills this in; the control plane never mutates it. Everything that
    happens to an order afterwards is recorded in the journal instead.
    """

    id: str
    task_id: str
    agent_id: str
    action: Action
    target: str
    args: dict[str, Any] = field(default_factory=dict)
    justification: str = ""
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def canonical(self) -> dict[str, Any]:
        """The exact bytes that get hashed and signed.

        ``created_at`` is normalised to UTC ISO-8601 with a trailing ``Z`` so
        that the same order always produces the same signature.
        """
        return {
            "id": self.id,
            "task_id": self.task_id,
            "agent_id": self.agent_id,
            "action": self.action.value,
            "target": self.target,
            "args": self.args,
            "justification": self.justification,
            "created_at": self.created_at.astimezone(timezone.utc)
            .isoformat()
            .replace("+00:00", "Z"),
        }


@dataclass(frozen=True)
class Decision:
    """The control plane's answer to an order, and why."""

    order: Order
    risk: Risk
    verdict: Verdict
    rule: str
    reason: str
    signature: str | None = None
    signature_pubkey: str | None = None
    decided_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


@dataclass
class Task:
    """A mission handed to the agent: one task = one sandbox lifetime."""

    id: str
    mission: str
    agent_id: str
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    state: str = "running"


__all__ = ["Action", "Decision", "Order", "Risk", "Task", "Verdict"]
