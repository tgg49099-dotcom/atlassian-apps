"""The replay — the verdicts of a journal, re-derived by the rules of today."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from signedorders.journal import Journal
from signedorders.models import Action, Order
from signedorders.policy import Policy, Rule
from signedorders.models import Risk
from signedorders.replay import replay_decisions


def make_order(action: Action, target: str) -> Order:
    return Order.new(
        task_id="task-1",
        agent_id="agent-1",
        action=action,
        target=target,
    )


@pytest.fixture()
def journal(tmp_path: Path) -> Journal:
    journal = Journal(tmp_path / "journal.jsonl")
    journal.append(
        {
            "kind": "decision",
            "task_id": "task-1",
            "order": make_order(Action.READ, "sandbox://sb-42:/app/main.py").canonical(),
            "risk": "low",
            "verdict": "auto_execute",
            "rule_id": "allow.sandbox_read",
        }
    )
    journal.append(
        {
            "kind": "decision",
            "task_id": "task-1",
            "order": make_order(Action.DELETE, "sandbox://sb-42:/srv/data").canonical(),
            "risk": "high",
            "verdict": "queued_for_human",
            "rule_id": "risk.destructive",
        }
    )
    return journal


def test_the_replay_reproduces_the_recorded_verdicts(journal):
    report = replay_decisions(journal)
    assert report.total == 2
    assert report.mismatches == []
    assert report.identical is True


def test_the_replay_sees_a_broken_chain(journal):
    entries = [entry.record for entry in journal.entries()]
    entries[0]["risk"] = "high"
    journal.path.write_text(
        "\n".join(json.dumps(record, sort_keys=True) for record in entries) + "\n",
        encoding="utf-8",
    )
    report = replay_decisions(journal)
    assert report.chain_ok is False
    assert report.chain_reason


def test_the_replay_detects_that_the_rules_changed(journal):
    """If the policy changes afterwards, the replay says so."""
    changed = Policy(
        rules=(
            Rule(
                id="allow.sandbox_read",
                risk=Risk.MEDIUM,
                explanation="Reading is now considered a mutation.",
                match=lambda order: order.action is Action.READ,
            ),
            Rule(
                id="default.unknown",
                risk=Risk.HIGH,
                explanation="Everything else waits for a human.",
                match=lambda order: True,
            ),
        )
    )
    report = replay_decisions(journal, changed)
    assert report.identical is False
    # both orders are re-classified by the new rules
    assert len(report.mismatches) == 2
    first = report.mismatches[0]
    assert first.order_id
    assert first.recorded_rule == "allow.sandbox_read"
    assert first.recorded_risk == "low"
    assert first.recomputed_risk == "medium"


def test_the_replay_ignores_the_other_records(journal):
    journal.append({"kind": "execution", "task_id": "task-1", "exit_code": 0})
    report = replay_decisions(journal)
    assert report.total == 2


def test_the_report_is_serialisable(journal):
    report = replay_decisions(journal)
    payload = report.to_dict()
    assert json.dumps(payload)
    assert payload["total"] == 2
    assert payload["chain_ok"] is True
