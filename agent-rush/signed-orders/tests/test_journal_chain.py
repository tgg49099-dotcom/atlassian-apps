"""The journal chain — the property that makes the claims checkable.

A third party must be able to re-compute the chain from a fresh clone, without
any secret, and get the same answer. These tests are the specification of that
property.

TODO(build-window): implement the assertions.
"""

from signedorders.journal import GENESIS, Journal


def test_append_then_verify(tmp_path):
    """A journal written normally verifies cleanly."""
    journal = Journal(tmp_path / "journal.jsonl")
    for index in range(5):
        journal.append({"kind": "decision", "index": index})
    ok, broken = journal.verify()
    assert ok is True
    assert broken is None


def test_tampering_is_detected(tmp_path):
    """Editing a record in the middle breaks the chain, and the break is located."""
    journal = Journal(tmp_path / "journal.jsonl")
    for index in range(5):
        journal.append({"kind": "decision", "payload": f"order-{index}"})
    lines = (tmp_path / "journal.jsonl").read_text(encoding="utf-8").splitlines()
    lines[2] = lines[2].replace("order-2", "order-X")
    (tmp_path / "journal.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    ok, broken = journal.verify()
    assert ok is False
    assert broken is not None


def test_first_record_links_to_genesis(tmp_path):
    """The first record points at the genesis hash, so nothing can be prepended."""
    journal = Journal(tmp_path / "journal.jsonl")
    entry = journal.append({"kind": "decision"})
    assert entry.previous_hash == GENESIS
    assert entry.index == 0
