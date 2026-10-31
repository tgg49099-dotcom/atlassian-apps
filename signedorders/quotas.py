"""The quotas — the budgets that keep one agent (and one task) finite.

Two families of limits:

* **window limits** — how many orders an agent may request per sliding window
  (10 per minute by default).  The window **slides**: an order that falls out
  of the window frees its slot immediately, it is not a fixed bucket that
  refills on a wall-clock boundary.
* **budget limits** — how much a task may consume in total (orders, CPU
  seconds, bytes written, model tokens, concurrent sandboxes).

A quota is a hard limit, not a warning: when it breaks, the order is refused
and the refusal is journalled.

The clock is **injectable**.  Every method that needs the time asks
``self.clock()``, so the tests drive a fake clock and never sleep:

    clock = FakeClock(0.0)
    quotas = QuotaManager(clock=clock)
    ...
    clock.advance(60.0)
"""

from __future__ import annotations

import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Any, Callable, Deque

__all__ = [
    "QuotaLimits",
    "QuotaManager",
    "QuotaVerdict",
    "SAFETY_LIMITS",
    "FakeClock",
]


#: Which limits are *safety* limits: breaking one also triggers the rollback.
SAFETY_LIMITS = frozenset({"concurrent_sandboxes", "wall_clock_seconds"})


@dataclass(frozen=True)
class QuotaLimits:
    """The limits applied to one agent (window) and to one task (budget)."""

    orders_per_window: int = 10
    window_seconds: float = 60.0
    orders_per_task: int = 40
    cpu_seconds_per_task: int = 120
    bytes_written_per_task: int = 1_048_576
    tokens_per_task: int = 200_000
    concurrent_sandboxes: int = 2
    wall_clock_seconds: int = 900


@dataclass
class QuotaVerdict:
    """The answer of the quota manager: allowed, and why not."""

    allowed: bool
    exceeded: list[str] = field(default_factory=list)
    snapshot: dict[str, Any] = field(default_factory=dict)

    @property
    def safety_violation(self) -> bool:
        """True when a broken limit requires the rollback of the task."""
        return any(name in SAFETY_LIMITS for name in self.exceeded)


class QuotaManager:
    """Keeps the counters of every agent and every task.

    ``clock`` returns a float number of seconds (monotonic by default).  It is
    injected so that the sliding window can be tested without waiting.
    """

    def __init__(
        self,
        limits: QuotaLimits | None = None,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self.limits = limits or QuotaLimits()
        self.clock: Callable[[], float] = clock or time.monotonic
        # agent_id -> timestamps of the orders inside the current window
        self._windows: dict[str, Deque[float]] = defaultdict(deque)
        # task_id -> accumulated usage
        self._tasks: dict[str, dict[str, int]] = defaultdict(
            lambda: {
                "orders": 0,
                "cpu_seconds": 0,
                "bytes_written": 0,
                "tokens": 0,
                "sandboxes_open": 0,
                "started_at": 0.0,
            }
        )

    # -- the sliding window ------------------------------------------------ #

    def _prune(self, agent_id: str, now: float) -> Deque[float]:
        """Drop the timestamps that left the window; return the live ones."""
        window = self._windows[agent_id]
        cutoff = now - self.limits.window_seconds
        while window and window[0] <= cutoff:
            window.popleft()
        return window

    def orders_in_window(self, agent_id: str) -> int:
        """How many orders the agent has inside the current window."""
        return len(self._prune(agent_id, self.clock()))

    def check(self, agent_id: str, task_id: str | None = None) -> QuotaVerdict:
        """Check the quotas **without** charging anything.

        Returns the verdict, the names of the broken limits and a snapshot of
        the counters (that snapshot goes into the signed order, so the reader
        knows under which budget the order was authorised).
        """
        now = self.clock()
        window = self._prune(agent_id, now)
        broken: list[str] = []

        if len(window) >= self.limits.orders_per_window:
            broken.append("orders_per_window")

        usage = self._tasks.get(task_id) if task_id else None
        if usage is not None:
            if usage["orders"] >= self.limits.orders_per_task:
                broken.append("orders_per_task")
            if usage["cpu_seconds"] >= self.limits.cpu_seconds_per_task:
                broken.append("cpu_seconds_per_task")
            if usage["bytes_written"] >= self.limits.bytes_written_per_task:
                broken.append("bytes_written_per_task")
            if usage["tokens"] >= self.limits.tokens_per_task:
                broken.append("tokens_per_task")
            if usage["sandboxes_open"] >= self.limits.concurrent_sandboxes:
                broken.append("concurrent_sandboxes")

        return QuotaVerdict(
            allowed=not broken,
            exceeded=broken,
            snapshot=self.snapshot(agent_id, task_id),
        )

    def charge_order(self, agent_id: str, task_id: str | None = None) -> None:
        """Record one order: it consumes a slot of the window and of the task."""
        now = self.clock()
        window = self._prune(agent_id, now)
        window.append(now)
        if task_id:
            usage = self._tasks[task_id]
            usage["orders"] += 1
            if usage["started_at"] == 0.0:
                usage["started_at"] = now

    def charge_usage(
        self,
        task_id: str,
        *,
        cpu_seconds: int = 0,
        bytes_written: int = 0,
        tokens: int = 0,
    ) -> None:
        """Record what an execution really consumed (not what it claimed)."""
        usage = self._tasks[task_id]
        usage["cpu_seconds"] += cpu_seconds
        usage["bytes_written"] += bytes_written
        usage["tokens"] += tokens

    def open_sandbox(self, task_id: str) -> None:
        self._tasks[task_id]["sandboxes_open"] += 1

    def close_sandbox(self, task_id: str) -> None:
        usage = self._tasks[task_id]
        usage["sandboxes_open"] = max(0, usage["sandboxes_open"] - 1)

    # -- introspection ----------------------------------------------------- #

    def snapshot(self, agent_id: str, task_id: str | None = None) -> dict[str, Any]:
        """The counters, as they are written into the signed order."""
        usage = self._tasks.get(task_id) if task_id else None
        return {
            "agent_orders_in_window": len(self._prune(agent_id, self.clock())),
            "orders_per_window": self.limits.orders_per_window,
            "window_seconds": self.limits.window_seconds,
            "task_orders": usage["orders"] if usage else 0,
            "task_cpu_seconds": usage["cpu_seconds"] if usage else 0,
            "task_bytes_written": usage["bytes_written"] if usage else 0,
            "task_tokens": usage["tokens"] if usage else 0,
            "task_sandboxes_open": usage["sandboxes_open"] if usage else 0,
        }

    def reset_task(self, task_id: str) -> None:
        """Forget a task (called when the task is closed)."""
        self._tasks.pop(task_id, None)


class FakeClock:
    """A clock that only moves when the test says so.

    It is part of the package (not of the tests) so that the *documentation*
    of the quota semantics can use it, and so that anybody writing a test for
    this project has the same tool.
    """

    def __init__(self, now: float = 0.0) -> None:
        self.now = float(now)

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> float:
        """Move the clock forward and return the new time."""
        self.now += float(seconds)
        return self.now
