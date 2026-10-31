"""Quotas — a budget that can be reset by restarting the process is not a budget.

TODO(build-window): implement the assertions and add the persistence test (the
counters must survive a restart of the control plane).
"""

from signedorders.quotas import QuotaLedger, QuotaLimits


def test_order_limit_is_enforced():
    """After N orders in a task, the next one is refused."""
    ledger = QuotaLedger("t1", QuotaLimits(orders_per_task=3))
    ledger.start_window(now=0.0)
    for _ in range(3):
        assert ledger.exceeded() == []
        ledger.charge_order()
    assert "orders_per_task" in ledger.exceeded()


def test_per_minute_window_resets():
    """The per-minute limit opens a new window instead of blocking forever."""
    ledger = QuotaLedger("t1", QuotaLimits(orders_per_minute=2, orders_per_task=100))
    ledger.start_window(now=0.0)
    ledger.charge_order()
    ledger.charge_order()
    assert "orders_per_minute" in ledger.exceeded()
    ledger.start_window(now=60.0)
    assert "orders_per_minute" not in ledger.exceeded()


def test_safety_limit_requests_a_rollback():
    """Too many concurrent sandboxes is a safety violation, not only a budget."""
    ledger = QuotaLedger("t1", QuotaLimits(concurrent_sandboxes=2))
    ledger.open_sandbox()
    ledger.open_sandbox()
    broken = ledger.exceeded()
    assert "concurrent_sandboxes" in broken
    assert ledger.is_safety_violation(broken) is True


def test_token_budget_is_charged():
    """The LLM spend of a task is charged to the task budget."""
    ledger = QuotaLedger("t1", QuotaLimits(tokens_per_task=100))
    ledger.charge_usage(tokens=60)
    assert "tokens_per_task" not in ledger.exceeded()
    ledger.charge_usage(tokens=60)
    assert "tokens_per_task" in ledger.exceeded()
