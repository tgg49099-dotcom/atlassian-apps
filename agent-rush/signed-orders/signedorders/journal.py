"""The journal — append-only, hash-chained, verifiable without any secret.

Every decision and every execution is appended to a JSONL file. Each record
carries the SHA-256 of the previous one, so the file cannot be edited in the
middle without breaking the chain:

    h_n = sha256( h_{n-1} || sha256(canonical_json(record_n)) )

``verify()`` re-computes the chain from a fresh clone and returns the first
broken index (or ``None``). It needs **no key and no server**: the point is
that a third party can check the story we tell about the agent.

The signature of the order is stored *in* the record; the public key that
verifies it is committed to the repository on purpose.

TODO(build-window): fsync on every append, and a rotation policy for long
tasks.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator

GENESIS = "0" * 64


def canonical_json(record: dict[str, Any]) -> bytes:
    """The exact bytes that are hashed: sorted keys, no whitespace, UTF-8."""
    return json.dumps(record, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def chain_hash(previous: str, record: dict[str, Any]) -> str:
    """The hash of a record, given the hash of the previous one.

    The record's own ``hash`` field is excluded from the computation, so that
    appending and verifying always agree on what was hashed.
    """
    payload = {key: value for key, value in record.items() if key != "hash"}
    digest = hashlib.sha256(canonical_json(payload)).hexdigest()
    return hashlib.sha256(previous.encode("ascii") + digest.encode("ascii")).hexdigest()


@dataclass
class JournalEntry:
    """One line of the journal."""

    index: int
    previous_hash: str
    hash: str
    record: dict[str, Any]

    @property
    def kind(self) -> str:
        return str(self.record.get("kind", ""))


class Journal:
    """Append-only JSONL journal with a hash chain."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)

    # -- writing ----------------------------------------------------------- #

    def append(self, record: dict[str, Any]) -> JournalEntry:
        """Append one record and return the stored entry."""
        entries = list(self.read())
        previous = entries[-1].hash if entries else GENESIS
        index = entries[-1].index + 1 if entries else 0
        stored = dict(record)
        stored["index"] = index
        stored["previous_hash"] = previous
        stored["hash"] = chain_hash(previous, stored)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(stored, sort_keys=True, ensure_ascii=False) + "\n")
        return JournalEntry(index, previous, stored["hash"], stored)

    # -- reading ----------------------------------------------------------- #

    def read(self) -> Iterator[JournalEntry]:
        if not self.path.exists():
            return iter(())
        return self._iter()

    def _iter(self) -> Iterator[JournalEntry]:
        with self.path.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                stored = json.loads(line)
                yield JournalEntry(
                    index=int(stored.get("index", 0)),
                    previous_hash=str(stored.get("previous_hash", GENESIS)),
                    hash=str(stored.get("hash", "")),
                    record=stored,
                )

    # -- checking ---------------------------------------------------------- #

    def verify(self) -> tuple[bool, int | None]:
        """Re-compute the whole chain.

        Returns ``(ok, first_broken_index)``. ``first_broken_index`` is
        ``None`` when the chain is intact.
        """
        previous = GENESIS
        expected_index = 0
        for entry in self.read():
            if entry.index != expected_index:
                return (False, entry.index)
            if entry.previous_hash != previous:
                return (False, entry.index)
            recomputed = chain_hash(previous, entry.record)
            if recomputed != entry.hash:
                return (False, entry.index)
            previous = entry.hash
            expected_index += 1
        return (True, None)

    def tail(self, count: int = 20) -> list[JournalEntry]:
        return list(self.read())[-count:]


__all__ = ["GENESIS", "Journal", "JournalEntry", "canonical_json", "chain_hash"]
