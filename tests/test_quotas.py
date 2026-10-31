"""The quotas — the sliding window, the budgets, and the clock that drives them."""

from __future__ import annotations

import pytest

from signedorders.quotas import FakeClock, QuotaLimits, QuotaManager


@pytest.fixture()
def clock() -> FakeClock:
    return FakeClock(0.0)


@pytest.fixture()
def quotas(clock: FakeClock) -> QuotaManager:
    return QuotaManager(
        limits=QuotaLimits(orders_per_window=10, window_seconds=60.0),
        clock=clock,
    )


# --------------------------------------------------------------------------- #
# The sliding window
# --------------------------------------------------------------------------- #


def test_the_first_order_is_allowed(quotas):
    verdict = quotas.check("agent-1")
    assert verdict.allowed is True
    assert verdict.exceeded == []


def test_ten_orders_per_minute_are_allowed(quotas):
    for _ in range(10):
        quotas.charge_order("agent-1")
    assert quotas.orders_in_window("agent-1") == 10


def test_the_eleventh_order_in_the_window_is_refused(quotas):
    for _ in range(10):
        quotas.charge_order("agent-1")
    verdict = quotas.check("agent-1")
    assert verdict.allowed is False
    assert "orders_per_window" in verdict.exceeded


def test_the_agents_are_counted_separately(quotas):
    for _ in range(10):
        quotas.charge_order("agent-1")
    assert quotas.check("agent-1").allowed is False
    assert quotas.check("agent-2").allowed is True


def test_the_window_slides_an_old_order_leaves_it(quotas, clock):
    for _ in range(10):
        quotas.charge_order("agent-1")
    assert quotas.check("agent-1").allowed is False
    clock.advance(60.0)
    assert quotas.orders_in_window("agent-1") == 0
    assert quotas.check("agent-1").allowed is True


def test_the_window_is_still_full_just_before_the_end(quotas, clock):
    for _ in range(10):
        quotas.charge_order("agent-1")
    clock.advance(59.999)
    assert quotas.orders_in_window("agent-1") == 10
    assert quotas.check("agent-1").allowed is False


def test_the_window_slides_partially(quotas, clock):
    """Six orders at t=0 and four at t=30: at t=60.5 only the four remain."""
    for _ in range(6):
        quotas.charge_order("agent-1")
    clock.advance(30.0)
    for _ in range(4):
        quotas.charge_order("agent-1")
    assert quotas.orders_in_window("agent-1") == 10
    clock.advance(30.5)
    assert quotas.orders_in_window("agent-1") == 4
    verdict = quotas.check("agent-1")
    assert verdict.allowed is True


def test_the_window_freezes_the_clock_it_is_given(quotas, clock):
    """The manager must never read the real clock."""
    for _ in range(10):
        quotas.charge_order("agent-1")
    clock.advance(1000.0)
    quotas.charge_order("agent-2")
    assert quotas.orders_in_window("agent-2") == 1
    assert quotas.orders_in_window("agent-1") == 0


# --------------------------------------------------------------------------- #
# The budgets of a task
# --------------------------------------------------------------------------- #


def test_a_task_has_its_own_order_budget(clock):
    quotas = QuotaManager(
        limits=QuotaLimits(orders_per_window=100, orders_per_task=3), clock=clock
    )
    for _ in range(3):
        quotas.charge_order("agent-1", "task-1")
    verdict = quotas.check("agent-1", "task-1")
    assert verdict.allowed is False
    assert "orders_per_task" in verdict.exceeded


def test_the_cpu_budget_is_enforced(clock):
    quotas = QuotaManager(
        limits=QuotaLimits(orders_per_window=100, cpu_seconds_per_task=10), clock=clock
    )
    quotas.charge_usage("task-1", cpu_seconds=10)
    verdict = quotas.check("agent-1", "task-1")
    assert verdict.allowed is False
    assert "cpu_seconds_per_task" in verdict.exceeded


def test_the_byte_budget_is_enforced(clock):
    quotas = QuotaManager(
        limits=QuotaLimits(orders_per_window=100, bytes_written_per_task=1024),
        clock=clock,
    )
    quotas.charge_usage("task-1", bytes_written=1024)
    verdict = quotas.check("agent-1", "task-1")
    assert "bytes_written_per_task" in verdict.exceeded


def test_the_token_budget_is_enforced(clock):
    quotas = QuotaManager(
        limits=QuotaLimits(orders_per_window=100, tokens_per_task=500), clock=clock
    )
    quotas.charge_usage("task-1", tokens=500)
    verdict = quotas.check("agent-1", "task-1")
    assert "tokens_per_task" in verdict.exceeded


def test_the_concurrency_budget_is_a_safety_limit(clock):
    quotas = QuotaManager(
        limits=QuotaLimits(orders_per_window=100, concurrent_sandboxes=2), clock=clock
    )
    quotas.open_sandbox("task-1")
    quotas.open_sandbox("task-1")
    verdict = quotas.check("agent-1", "task-1")
    assert "concurrent_sandboxes" in verdict.exceeded
    assert verdict.safety_violation is True


def test_closing_a_sandbox_frees_a_slot(clock):
    quotas = QuotaManager(
        limits=QuotaLimits(orders_per_window=100, concurrent_sandboxes=1), clock=clock
    )
    quotas.open_sandbox("task-1")
    assert quotas.check("agent-1", "task-1").allowed is False
    quotas.close_sandbox("task-1")
    assert quotas.check("agent-1", "task-1").allowed is True


def test_a_broken_budget_is_not_a_safety_violation(clock):
    quotas = QuotaManager(
        limits=QuotaLimits(orders_per_window=100, orders_per_task=1), clock=clock
    )
    quotas.charge_order("agent-1", "task-1")
    verdict = quotas.check("agent-1", "task-1")
    assert verdict.allowed is False
    assert verdict.safety_violation is False


# --------------------------------------------------------------------------- #
# The snapshot
# --------------------------------------------------------------------------- #


def test_the_snapshot_reports_the_counters(quotas):
    quotas.charge_order("agent-1", "task-1")
    quotas.charge_usage("task-1", cpu_seconds=3, bytes_written=10, tokens=20)
    snapshot = quotas.snapshot("agent-1", "task-1")
    assert snapshot["agent_orders_in_window"] == 1
    assert snapshot["orders_per_window"] == 10
    assert snapshot["task_orders"] == 1
    assert snapshot["task_cpu_seconds"] == 3
    assert snapshot["task_bytes_written"] == 10
    assert snapshot["task_tokens"] == 20


def test_the_snapshot_is_part_of_the_verdict(quotas):
    verdict = quotas.check("agent-1", "task-1")
    assert verdict.snapshot["orders_per_window"] == 10
    assert verdict.snapshot["window_seconds"] == 60.0


def test_the_fake_clock_only_moves_when_asked():
    clock = FakeClock(10.0)
    assert clock() == 10.0
    assert clock.advance(2.5) == 12.5
    assert clock() == 12.5


def test_a_task_can_be_reset(quotas):
    quotas.charge_order("agent-1", "task-1")
    quotas.reset_task("task-1")
    assert quotas.snapshot("agent-1", "task-1")["task_orders"] == 0
