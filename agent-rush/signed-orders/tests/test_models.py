"""The order contract — the same order must always produce the same bytes.

The signature is computed over :meth:`Order.canonical`, so the canonical form
is part of the public contract of the project.

TODO(build-window): implement the assertions.
"""

from datetime import datetime, timezone

from signedorders.models import Action, Order


def make_order() -> Order:
    return Order(
        id="so_1",
        task_id="t1",
        agent_id="a1",
        action=Action.READ,
        target="/app/config.py",
        args={"path": "/app/config.py"},
        justification="read the configuration to find the cause",
        created_at=datetime(2026, 11, 5, 12, 0, tzinfo=timezone.utc),
    )


def test_canonical_is_stable():
    """Two identical orders produce identical canonical bytes."""
    assert make_order().canonical() == make_order().canonical()


def test_canonical_normalises_the_timestamp():
    """The timestamp is UTC ISO-8601 with a trailing Z, whatever the offset."""
    other = Order(**{**make_order().__dict__,
                      "created_at": datetime(2026, 11, 5, 13, 0,
                                             tzinfo=timezone.utc)})
    assert other.canonical()["created_at"].endswith("Z")
    assert other.canonical()["created_at"] == "2026-11-05T13:00:00Z"


def test_canonical_does_not_contain_the_secret():
    """The canonical form carries only the order, never the control plane's keys."""
    canonical = make_order().canonical()
    assert set(canonical) == {
        "id", "task_id", "agent_id", "action", "target", "args",
        "justification", "created_at",
    }
