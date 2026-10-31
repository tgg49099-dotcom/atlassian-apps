"""Quotas — the budgets that keep one task (or one agent) finite.

A quota is a hard limit, not a warning: when a limit is reached the order is
**refused**, and a safety limit additionally triggers the automatic rollback of
the task.

Two families of limits:

* *budget* limits (orders per minute, orders per task, CPU-seconds, bytes
  written, model tokens) -> the order is refused;
* *safety* limits (concurrent sandboxes, wall-clock timeout) -> the order is
  refused **and** the task is rolled back.

TODO(build-window): the counters must be persisted (SQLite) so that a restart
of the control plane cannot be used to reset a budget.
"""

from __future__ import annotations

from dataclasses import dataclass, field

#: Which limits are safety limits (they trigger the rollback).
SAFETY_LIMITS = frozenset({"concurrent_sandboxes"})


@dataclass
class QuotaLimits:
    """The limits applied to one task (and to one agent, per minute)."""

    orders_per_minute: int = 10
    orders_per_task: int = 40
    cpu_seconds_per_task: int = 120
    bytes_written_per_task: int = 1_048_576
    concurrent_sandboxes: int = 2
    tokens_per_task: int = 200_000
    wall_clock_seconds: int = 900


@dataclass
class QuotaLedger:
    """Live counters for one task.

    The ledger is updated by the executor after every order, so it knows what
    the order actually consumed (CPU seconds, bytes, tokens) rather than what
    it claimed.
    """

    task_id: str
    limits: QuotaLimits = field(default_factory=QuotaLimits)
    orders_this_minute: int = 0
    orders_total: int = 0
    cpu_seconds: int = 0
    bytes_written: int = 0
    tokens: int = 0
    sandboxes_open: int = 0
    _window_started_at: float = 0.0

    # -- accounting -------------------------------------------------------- #

    def start_window(self, now: float) -> None:
        """Open a new one-minute window for the per-minute limit."""
        self._window_started_at = now
        self.orders_this_minute = 0

    def charge_order(self) -> None:
        self.orders_this_minute += 1
        self.orders_total += 1

    def charge_usage(self, *, cpu_seconds: int = 0, bytes_written: int = 0,
                     tokens: int = 0) -> None:
        self.cpu_seconds += cpu_seconds
        self.bytes_written += bytes_written
        self.tokens += tokens

    def open_sandbox(self) -> None:
        self.sandboxes_open += 1

    def close_sandbox(self) -> None:
        self.sandboxes_open = max(0, self.sandboxes_open - 1)

    # -- checks ------------------------------------------------------------ #

    def exceeded(self) -> list[str]:
        """Return the names of the limits that are currently exceeded."""
        broken: list[str] = []
        if self.orders_this_minute >= self.limits.orders_per_minute:
            broken.append("orders_per_minute")
        if self.orders_total >= self.limits.orders_per_task:
            broken.append("orders_per_task")
        if self.cpu_seconds >= self.limits.cpu_seconds_per_task:
            broken.append("cpu_seconds_per_task")
        if self.bytes_written >= self.limits.bytes_written_per_task:
            broken.append("bytes_written_per_task")
        if self.tokens >= self.limits.tokens_per_task:
            broken.append("tokens_per_task")
        if self.sandboxes_open >= self.limits.concurrent_sandboxes:
            broken.append("concurrent_sandboxes")
        return broken

    def is_safety_violation(self, broken: list[str]) -> bool:
        """True when at least one broken limit requires a rollback."""
        return any(name in SAFETY_LIMITS for name in broken)


__all__ = ["SAFETY_LIMITS", "QuotaLedger", "QuotaLimits"]
