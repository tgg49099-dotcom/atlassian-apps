"""The journal — the hash chain, the offline verification and the replay."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from signedorders.journal import GENESIS, Journal, chain_hash, record_hash


@pytest.fixture()
def journal(tmp_path: Path) -> Journal:
    return Journal(tmp_path / "journal.jsonl")


def _write(journal: Journal, records: list[dict]) -> None:
    for record in records:
        journal.append(record)


def _rewrite(path: Path, records: list[dict]) -> None:
    path.write_text(
        "\n".join(json.dumps(record, sort_keys=True) for record in records) + "\n",
        encoding="utf-8",
    )


# --------------------------------------------------------------------------- #
# The chain
# --------------------------------------------------------------------------- #


def test_an_empty_journal_is_valid(journal):
    report = journal.verify()
    assert report.ok is True
    assert report.entries_checked == 0
    assert report.broken_index is None


def test_the_first_record_chains_on_the_genesis(journal):
    entry = journal.append({"kind": "decision", "order_id": "so_1"})
    assert entry.previous_hash == GENESIS
    assert entry.index == 0


def test_each_record_carries_the_hash_of_the_previous_one(journal):
    first = journal.append({"kind": "decision", "order_id": "so_1"})
    second = journal.append({"kind": "execution", "order_id": "so_1"})
    assert second.previous_hash == first.hash
    assert second.hash != first.hash


def test_the_chain_is_valid_after_writes(journal):
    _write(journal, [{"kind": "decision", "n": index} for index in range(5)])
    report = journal.verify()
    assert report.ok is True
    assert report.entries_checked == 5


def test_the_hash_excludes_the_hash_field_itself(journal):
    """A record must hash the same with or without its own 'hash' field."""
    record = {"kind": "decision", "n": 1, "hash": "whatever"}
    assert record_hash(record) == record_hash({"kind": "decision", "n": 1})


def test_the_chain_hash_depends_on_the_previous_hash():
    record = {"kind": "decision", "n": 1}
    assert chain_hash(GENESIS, record) != chain_hash("f" * 64, record)


# --------------------------------------------------------------------------- #
# The attacks
# --------------------------------------------------------------------------- #


def test_an_edited_record_is_detected(journal):
    _write(journal, [{"kind": "decision", "n": index} for index in range(4)])
    entries = [entry.record for entry in journal.entries()]
    entries[1]["n"] = 999          # somebody rewrote the history
    _rewrite(journal.path, entries)

    report = journal.verify()
    assert report.ok is False
    assert report.broken_index == 1
    assert "modified" in report.reason
    assert str(report.broken_index) in report.reason


def test_a_removed_record_is_detected(journal):
    _write(journal, [{"kind": "decision", "n": index} for index in range(4)])
    entries = [entry.record for entry in journal.entries()]
    del entries[2]
    _rewrite(journal.path, entries)

    report = journal.verify()
    assert report.ok is False
    assert "removed or inserted" in report.reason


def test_an_inserted_record_is_detected(journal):
    _write(journal, [{"kind": "decision", "n": index} for index in range(3)])
    entries = [entry.record for entry in journal.entries()]
    entries.insert(
        1,
        {
            "index": 1,
            "kind": "decision",
            "n": "forged",
            "previous_hash": entries[0]["hash"],
            "hash": "0" * 64,
        },
    )
    _rewrite(journal.path, entries)

    report = journal.verify()
    assert report.ok is False
    assert report.broken_index is not None
    assert "removed or inserted" in report.reason


def test_reordered_records_are_detected(journal):
    _write(journal, [{"kind": "decision", "n": index} for index in range(4)])
    entries = [entry.record for entry in journal.entries()]
    entries[1], entries[2] = entries[2], entries[1]
    _rewrite(journal.path, entries)

    report = journal.verify()
    assert report.ok is False
    assert "reordered" in report.reason


def test_a_truncated_journal_is_still_valid(journal):
    """Removing the last records is not an attack: the chain is shorter."""
    _write(journal, [{"kind": "decision", "n": index} for index in range(4)])
    entries = [entry.record for entry in journal.entries()]
    _rewrite(journal.path, entries[:2])
    report = journal.verify()
    assert report.ok is True
    assert report.entries_checked == 2


def test_a_corrupted_line_is_reported(journal):
    _write(journal, [{"kind": "decision", "n": 0}])
    with journal.path.open("a", encoding="utf-8") as handle:
        handle.write("{this is not json}\n")
    from signedorders.journal import JournalCorrupted

    with pytest.raises(JournalCorrupted):
        journal.verify()


# --------------------------------------------------------------------------- #
# The replay
# --------------------------------------------------------------------------- #


def test_the_replay_reads_the_journal_back_in_order(journal):
    _write(
        journal,
        [
            {"kind": "decision", "task_id": "task-1", "n": 0},
            {"kind": "approval", "task_id": "task-1", "n": 1},
            {"kind": "execution", "task_id": "task-1", "n": 2},
            {"kind": "decision", "task_id": "task-2", "n": 3},
        ],
    )
    result = journal.replay()
    assert result.count == 4
    assert result.kinds == {"decision": 2, "approval": 1, "execution": 1}
    assert result.tasks == ["task-1", "task-2"]
    assert [entry.index for entry in result.entries] == [0, 1, 2, 3]


def test_the_replay_of_an_empty_journal_is_empty(journal):
    result = journal.replay()
    assert result.count == 0
    assert result.tasks == []


# --------------------------------------------------------------------------- #
# Offline
# --------------------------------------------------------------------------- #


def test_the_verification_needs_no_key_and_no_server(journal):
    """The module must not import anything that could need a secret."""
    import inspect

    from signedorders import journal as journal_module

    source = inspect.getsource(journal_module)
    for needle in ("import requests", "import urllib", "import socket", "openai"):
        assert needle not in source
    _write(journal, [{"kind": "decision", "n": 0}])
    assert journal.verify().ok is True
