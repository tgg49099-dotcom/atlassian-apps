"""The journal — append-only, hash-chained, verifiable without any secret.

Every decision and every execution is appended to a JSONL file.  Each record
carries the hash of the previous one, so the file cannot be edited in the
middle without breaking the chain:

    h_n = sha256( h_{n-1} || sha256(canonical_json(record_n)) )

``verify()`` re-computes the chain from scratch and returns **the first broken
index and the reason**.  It needs no key and no server: the point is that a
third party can check the story we tell about the agent.

The chain detects four different attacks, and the report says which one it is:

* a record was **edited**  -> the recomputed hash differs;
* a record was **removed** -> the indices skip a number;
* a record was **inserted** -> the indices skip a number the other way;
* the records were **reordered** -> ``previous_hash`` does not match.

``replay()`` reads the journal back in order and rebuilds the sequence of
events, which is what the CLI prints when a judge wants the story without
running anything.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterator

from .canonical import canonical_json

__all__ = [
    "GENESIS",
    "ChainReport",
    "Journal",
    "JournalEntry",
    "ReplayResult",
    "chain_hash",
    "record_hash",
]

#: The hash of "the record before the first one".
GENESIS = "0" * 64


def record_hash(record: dict[str, Any]) -> str:
    """The hash of a record's own content (the ``hash`` field excluded)."""
    payload = {key: value for key, value in record.items() if key != "hash"}
    return hashlib.sha256(canonical_json(payload)).hexdigest()


def chain_hash(previous: str, record: dict[str, Any]) -> str:
    """The hash of a record, chained on the hash of the previous one."""
    digest = record_hash(record)
    return hashlib.sha256(
        previous.encode("ascii") + digest.encode("ascii")
    ).hexdigest()


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

    def to_dict(self) -> dict[str, Any]:
        return dict(self.record)


@dataclass
class ChainReport:
    """The result of verifying the chain.

    ``reason`` is written for a human: it must say **what** is broken and
    **where**, because "the verification failed" is not an audit trail.
    """

    ok: bool
    entries_checked: int = 0
    broken_index: int | None = None
    reason: str = ""

    def __bool__(self) -> bool:
        return self.ok

    def to_dict(self) -> dict[str, Any]:
        return {
            "ok": self.ok,
            "entries_checked": self.entries_checked,
            "broken_index": self.broken_index,
            "reason": self.reason,
        }


@dataclass
class ReplayResult:
    """The result of replaying a journal."""

    entries: list[JournalEntry] = field(default_factory=list)
    kinds: dict[str, int] = field(default_factory=dict)
    tasks: list[str] = field(default_factory=list)

    @property
    def count(self) -> int:
        return len(self.entries)

    def to_dict(self) -> dict[str, Any]:
        return {
            "entries": self.count,
            "kinds": dict(self.kinds),
            "tasks": list(self.tasks),
        }


class Journal:
    """An append-only JSONL journal with a hash chain."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)

    # -- writing ----------------------------------------------------------- #

    def append(self, record: dict[str, Any]) -> JournalEntry:
        """Append one record and return the stored entry."""
        entries = list(self.entries())
        previous = entries[-1].hash if entries else GENESIS
        index = entries[-1].index + 1 if entries else 0
        stored = dict(record)
        stored["index"] = index
        stored["previous_hash"] = previous
        stored["hash"] = chain_hash(previous, stored)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(stored, sort_keys=True, ensure_ascii=False) + "\n")
            handle.flush()
        return JournalEntry(index, previous, stored["hash"], stored)

    # -- reading ----------------------------------------------------------- #

    def entries(self) -> list[JournalEntry]:
        """Every entry, in order."""
        return list(self._iter())

    def _iter(self) -> Iterator[JournalEntry]:
        if not self.path.exists():
            return
        with self.path.open(encoding="utf-8") as handle:
            for number, line in enumerate(handle, start=1):
                line = line.strip()
                if not line:
                    continue
                try:
                    stored = json.loads(line)
                except json.JSONDecodeError:
                    raise JournalCorrupted(f"line {number} is not valid JSON") from None
                yield JournalEntry(
                    index=int(stored.get("index", -1)),
                    previous_hash=str(stored.get("previous_hash", "")),
                    hash=str(stored.get("hash", "")),
                    record=stored,
                )

    def head(self) -> str:
        """The hash of the last entry (the anchor of the chain)."""
        entries = self.entries()
        return entries[-1].hash if entries else GENESIS

    # -- checking ---------------------------------------------------------- #

    def verify(self) -> ChainReport:
        """Re-compute the whole chain, offline, without any key.

        The diagnosis distinguishes the four attacks:

        * the set of indices is not contiguous -> a record was **removed or
          inserted**;
        * the set is complete but the order is wrong -> the records were
          **reordered**;
        * the chain link is broken -> the records were **reordered**;
        * the recomputed hash differs -> a record was **modified**.
        """
        entries = self.entries()
        indices = [entry.index for entry in entries]

        if sorted(indices) != list(range(len(entries))):
            broken = next(
                (
                    position
                    for position, entry in enumerate(entries)
                    if entry.index != position
                ),
                0,
            )
            return ChainReport(
                ok=False,
                entries_checked=0,
                broken_index=broken,
                reason=(
                    f"the records are not a contiguous sequence (entry {broken} has "
                    f"the index {entries[broken].index}): a record was removed or "
                    "inserted"
                ),
            )

        previous = GENESIS
        for position, entry in enumerate(entries):
            if entry.index != position:
                return ChainReport(
                    ok=False,
                    entries_checked=position,
                    broken_index=position,
                    reason=(
                        f"entry {position} carries the index {entry.index}: the "
                        "records were reordered"
                    ),
                )
            if entry.previous_hash != previous:
                return ChainReport(
                    ok=False,
                    entries_checked=position,
                    broken_index=position,
                    reason=(
                        f"entry {position} does not chain on the previous one: it "
                        f"references {entry.previous_hash[:12]}…, the chain expects "
                        f"{previous[:12]}… (the records were reordered)"
                    ),
                )
            recomputed = chain_hash(previous, entry.record)
            if recomputed != entry.hash:
                return ChainReport(
                    ok=False,
                    entries_checked=position,
                    broken_index=position,
                    reason=(
                        f"entry {position} was modified after it was written: the "
                        f"recomputed hash is {recomputed[:12]}…, the file says "
                        f"{entry.hash[:12]}…"
                    ),
                )
            previous = entry.hash
        return ChainReport(ok=True, entries_checked=len(entries))

    # -- replaying --------------------------------------------------------- #

    def replay(self) -> ReplayResult:
        """Read the journal back and rebuild the sequence of events."""
        result = ReplayResult()
        for entry in self.entries():
            result.entries.append(entry)
            kind = entry.kind or "unknown"
            result.kinds[kind] = result.kinds.get(kind, 0) + 1
            task_id = entry.record.get("task_id")
            if isinstance(task_id, str) and task_id not in result.tasks:
                result.tasks.append(task_id)
        return result


class JournalCorrupted(RuntimeError):
    """Raised when the journal file is not readable as JSONL."""


__all__.append("JournalCorrupted")
