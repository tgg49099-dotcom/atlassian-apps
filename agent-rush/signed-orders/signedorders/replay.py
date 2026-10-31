"""Replay — read the journal back and re-derive the verdicts.

The journal is not a log file: it is the artefact that lets somebody check a
claim instead of believing it.  Two levels of replay live here:

**decision replay** (this module) — every decision recorded in the journal is
re-evaluated with the policy of today, and the recorded verdict is compared
with the recomputed one.  If the rules changed after the fact, the replay says
so, with the order id and both verdicts.  This is what proves that the
history was not rewritten *and* that it was decided by the rules it claims.

**sandbox replay** (the build window) — the recorded orders are run again in a
fresh sandbox and their outputs are compared with the recorded ones.  The
comparison table is the same shape as this one: a mismatch must be visible,
not hidden.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .journal import Journal
from .models import Action, Order
from .policy import Policy, default_policy

__all__ = ["DecisionReplay", "ReplayReport", "replay_decisions"]


@dataclass
class DecisionReplay:
    """One decision, re-derived from the recorded order."""

    order_id: str
    recorded_rule: str
    recomputed_rule: str
    recorded_risk: str
    recomputed_risk: str
    matches: bool

    def to_dict(self) -> dict[str, Any]:
        return {
            "order_id": self.order_id,
            "recorded_rule": self.recorded_rule,
            "recomputed_rule": self.recomputed_rule,
            "recorded_risk": self.recorded_risk,
            "recomputed_risk": self.recomputed_risk,
            "matches": self.matches,
        }


@dataclass
class ReplayReport:
    """The result of replaying the decisions of a journal."""

    decisions: list[DecisionReplay] = field(default_factory=list)
    chain_ok: bool = True
    chain_reason: str = ""

    @property
    def total(self) -> int:
        return len(self.decisions)

    @property
    def mismatches(self) -> list[DecisionReplay]:
        return [decision for decision in self.decisions if not decision.matches]

    @property
    def identical(self) -> bool:
        """True when the chain is intact and every verdict is reproduced."""
        return self.chain_ok and not self.mismatches

    def to_dict(self) -> dict[str, Any]:
        return {
            "chain_ok": self.chain_ok,
            "chain_reason": self.chain_reason,
            "total": self.total,
            "mismatches": [mismatch.to_dict() for mismatch in self.mismatches],
        }


def _order_from_record(record: dict[str, Any]) -> Order | None:
    """Rebuild an :class:`Order` from the mapping stored in the journal."""
    from datetime import datetime

    try:
        action = Action(record["action"])
    except (KeyError, ValueError):
        return None
    created = record.get("created_at")
    try:
        created_at = (
            datetime.fromisoformat(str(created).replace("Z", "+00:00"))
            if created
            else datetime.now().astimezone()
        )
    except ValueError:
        return None
    return Order(
        id=str(record.get("id", "")),
        task_id=str(record.get("task_id", "")),
        agent_id=str(record.get("agent_id", "")),
        action=action,
        target=str(record.get("target", "")),
        args=dict(record.get("args") or {}),
        justification=str(record.get("justification", "")),
        created_at=created_at,
    )


def replay_decisions(journal: Journal, policy: Policy | None = None) -> ReplayReport:
    """Re-derive every decision of the journal with the policy of today."""
    active = policy or default_policy()
    report = ReplayReport()

    chain = journal.verify()
    report.chain_ok = chain.ok
    report.chain_reason = chain.reason

    for entry in journal.entries():
        if entry.kind != "decision":
            continue
        order_record = entry.record.get("order")
        if not isinstance(order_record, dict):
            continue
        order = _order_from_record(order_record)
        if order is None:
            continue
        risk, rule_id, _ = active.classify(order)
        recorded_rule = str(entry.record.get("rule_id", ""))
        recorded_risk = str(entry.record.get("risk", ""))
        report.decisions.append(
            DecisionReplay(
                order_id=order.id,
                recorded_rule=recorded_rule,
                recomputed_rule=rule_id,
                recorded_risk=recorded_risk,
                recomputed_risk=risk.value,
                matches=(recorded_rule == rule_id and recorded_risk == risk.value),
            )
        )
    return report
