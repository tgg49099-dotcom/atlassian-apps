"""The command line — what a judge runs to check us."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from signedorders.cli import main
from signedorders.journal import Journal


@pytest.fixture()
def journal(tmp_path: Path) -> Journal:
    journal = Journal(tmp_path / "journal.jsonl")
    journal.append({"kind": "decision", "task_id": "task-1", "n": 0})
    journal.append({"kind": "execution", "task_id": "task-1", "n": 1})
    return journal


def test_verify_returns_zero_on_an_intact_journal(journal, capsys):
    assert main(["verify", str(journal.path)]) == 0
    assert "intact" in capsys.readouterr().out


def test_verify_returns_one_on_a_broken_journal(journal, capsys):
    entries = [entry.record for entry in journal.entries()]
    entries[0]["n"] = 999
    journal.path.write_text(
        "\n".join(json.dumps(record, sort_keys=True) for record in entries) + "\n",
        encoding="utf-8",
    )
    assert main(["verify", str(journal.path)]) == 1
    output = capsys.readouterr().out
    assert "FAILED" in output
    assert "modified" in output


def test_replay_prints_the_story(journal, capsys):
    assert main(["replay", str(journal.path)]) == 0
    output = capsys.readouterr().out
    assert "2 entries replayed" in output
    assert "decision: 1" in output
    assert "task-1" in output


def test_policy_prints_the_rule_table(capsys):
    assert main(["policy"]) == 0
    table = json.loads(capsys.readouterr().out)
    assert len(table) >= 10
    assert all("id" in rule and "explanation" in rule for rule in table)


def test_init_key_writes_a_key(tmp_path, capsys):
    path = tmp_path / "signing.key"
    assert main(["init-key", str(path)]) == 0
    assert path.exists()
    assert path.stat().st_mode & 0o777 == 0o600
    assert "public key" in capsys.readouterr().out


def test_sign_prints_an_envelope(tmp_path, capsys):
    key = tmp_path / "signing.key"
    main(["init-key", str(key)])
    capsys.readouterr()          # drop the output of init-key
    payload = tmp_path / "payload.json"
    payload.write_text(json.dumps({"action": "read"}), encoding="utf-8")
    assert main(["sign", str(payload), "--key", str(key)]) == 0
    envelope = json.loads(capsys.readouterr().out)
    assert envelope["algorithm"] == "ed25519"
    assert envelope["signature"]


def test_preflight_finds_nothing_in_the_project():
    root = Path(__file__).resolve().parent.parent
    assert main(["preflight", str(root)]) == 0
