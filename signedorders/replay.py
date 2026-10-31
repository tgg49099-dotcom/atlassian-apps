"""Replay — run a recorded journal again and compare.

The journal is not a log file: it is the artefact that lets somebody check a
claim instead of believing it. ``replay`` takes the journal of a task, runs the
recorded orders again **in a fresh sandbox**, and compares the outputs with
what was recorded.

The result is a diff table (per order: recorded exit code vs replayed exit
code), so a mismatch is visible instead of hidden.

TODO(build-window): the replay loop, the comparison rules and the report.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .journal import Journal
from .sandbox.base import SandboxBackend


@dataclass
class ReplayResult:
    """The outcome of a replay."""

    task_id: str
    orders_replayed: int = 0
    orders_matched: int = 0
    mismatches: list[dict] = field(default_factory=list)
    verdict: str = "unknown"   # "identical" | "divergent" | "inconclusive"

    @property
    def identical(self) -> bool:
        return self.verdict == "identical"


def replay(journal: Journal, task_id: str, backend: SandboxBackend) -> ReplayResult:
    """Replay every order of ``task_id`` in a fresh sandbox."""
    raise NotImplementedError("TODO(build-window)")


__all__ = ["ReplayResult", "replay"]
