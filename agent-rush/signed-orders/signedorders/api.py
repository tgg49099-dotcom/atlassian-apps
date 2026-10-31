"""The HTTP API — the control plane's only public surface.

Routes:

======================================  =======================================
``POST   /orders``                      the agent requests an order (its only
                                        privilege; the key it holds is scoped
                                        to "request", nothing else)
``GET    /orders``                      the UI lists the orders and verdicts
``POST   /orders/{id}/approve``         a human approves a queued order
``POST   /orders/{id}/refuse``          a human refuses a queued order
``GET    /tasks/{id}/journal``          the hash-chained journal of a task
``GET    /journal/verify``              re-computes the chain (no secret)
``GET    /quotas``                      live counters
``GET    /stream``                      Server-Sent Events for the UI
======================================  =======================================

The API never executes a shell command itself: it decides, signs, queues and
records; the executor runs in a separate process.

TODO(build-window): the FastAPI app, the dependency injection of the services,
the SSE endpoint and the authentication middleware (agent key vs human key).
"""

from __future__ import annotations

from fastapi import FastAPI

from .journal import Journal
from .models import Order
from .orders import OrderService
from .signer import Signer

app = FastAPI(
    title="Signed Orders",
    version="0.1.0",
    description=(
        "Containment-first control plane for autonomous agents: every "
        "privileged action becomes a written, signed, verifiable order."
    ),
)


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness probe for the Vultr VM and for the demo URL."""
    return {"status": "ok"}


@app.post("/orders")
def request_order(order: Order) -> dict:
    """The agent asks for an order. It never gets an execution here."""
    raise NotImplementedError("TODO(build-window)")


@app.get("/orders")
def list_orders() -> dict:
    raise NotImplementedError("TODO(build-window)")


@app.post("/orders/{order_id}/approve")
def approve(order_id: str) -> dict:
    raise NotImplementedError("TODO(build-window)")


@app.post("/orders/{order_id}/refuse")
def refuse(order_id: str) -> dict:
    raise NotImplementedError("TODO(build-window)")


@app.get("/tasks/{task_id}/journal")
def task_journal(task_id: str) -> dict:
    raise NotImplementedError("TODO(build-window)")


@app.get("/journal/verify")
def verify_journal() -> dict:
    """Verify the chain of the journal — the public, secret-free check."""
    raise NotImplementedError("TODO(build-window)")


@app.get("/quotas")
def quotas() -> dict:
    raise NotImplementedError("TODO(build-window)")


@app.get("/stream")
def stream() -> dict:
    """Server-Sent Events: orders, verdicts, quota counters, journal lines."""
    raise NotImplementedError("TODO(build-window)")


def build_services(journal_path: str, signing_key_path: str) -> tuple[OrderService, Journal]:
    """Wire the services together (used by the entry point and by the tests)."""
    journal = Journal(journal_path)
    service = OrderService(journal=journal, signer=Signer(signing_key_path))
    return (service, journal)


__all__ = ["app", "build_services"]
