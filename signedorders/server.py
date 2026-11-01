"""The HTTP door of the control plane.

A small, dependency-free API (the standard library only) so the service can
be started on a Vultr VM with nothing but Python:

    signedorders serve --host 0.0.0.0 --port 8000

What it exposes:

======================================  ======  ==================================
route                                   method  what it does
======================================  ======  ==================================
``/health``                             GET     liveness, the public key, the counters
``/policy``                             GET     the rule table (no secret)
``/quotas``                             GET     the live counters of an agent
``/orders``                             GET     the pending orders and the decisions
``/orders``                             POST    **the only door of the agent**
``/orders/<id>/approve``                POST    the human signs the approval
``/orders/<id>/reject``                 POST    the human refuses
``/journal/verify``                     GET     the hash chain, verified live
======================================  ======  ==================================

Two principles are enforced **here**, not only in the library:

* an unauthenticated request never reaches the control plane — the agent key
  and the operator key are read from the environment, and a missing key
  disables the endpoint (it does not open it);
* no secret is ever returned: the public key is served, the private key is
  not, and the sandbox never receives any environment variable.
"""

from __future__ import annotations

import json
import secrets
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from .control import ControlPlane
from .journal import Journal
from .models import Action, Order
from .policy import default_policy
from .quotas import QuotaLimits, QuotaManager
from .signer import Signer, generate_keypair

__all__ = ["ControlPlaneServer", "build_server", "serve"]

MAX_BODY_BYTES = 64 * 1024


class ControlPlaneServer(ThreadingHTTPServer):
    """The HTTP server, with the control plane it serves."""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        address: tuple[str, int],
        control_plane: ControlPlane,
        agent_key: str | None = None,
        operator_key: str | None = None,
    ) -> None:
        super().__init__(address, _Handler)
        self.control_plane = control_plane
        self.agent_key = agent_key
        self.operator_key = operator_key
        self.decisions: list[dict[str, Any]] = []


class _Handler(BaseHTTPRequestHandler):
    """The request handler."""

    server_version = "signedorders"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    # -- plumbing --------------------------------------------------------- #

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        """Quiet: the journal is the log, the journal is hashed."""

    @property
    def _server(self) -> ControlPlaneServer:
        return self.server  # type: ignore[return-value]

    def _send(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, sort_keys=True, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        if length > MAX_BODY_BYTES:
            raise ValueError("the body is too big")
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError(f"invalid JSON: {error}") from error
        if not isinstance(data, dict):
            raise ValueError("the body must be a JSON object")
        return data

    def _authorised(self, expected: str | None) -> bool:
        """Check the bearer token, in constant time."""
        if not expected:
            return False
        header = self.headers.get("Authorization", "")
        prefix = "Bearer "
        if not header.startswith(prefix):
            return False
        return secrets.compare_digest(header[len(prefix):].strip(), expected)

    # -- GET --------------------------------------------------------------- #

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        route = parsed.path.rstrip("/") or "/"
        query = parse_qs(parsed.query)
        try:
            if route == "/health":
                self._send(200, self._health())
            elif route == "/policy":
                self._send(200, {"rules": default_policy().describe()})
            elif route == "/quotas":
                self._send(200, self._quotas(query))
            elif route == "/orders":
                self._send(200, self._orders())
            elif route == "/journal/verify":
                self._send(200, self._verify())
            else:
                self._send(404, {"error": "unknown route", "route": route})
        except Exception as error:  # pragma: no cover - defensive
            self._send(500, {"error": str(error)})

    # -- POST -------------------------------------------------------------- #

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        route = parsed.path.rstrip("/") or "/"
        try:
            body = self._read_json()
        except ValueError as error:
            self._send(400, {"error": str(error)})
            return

        if route == "/orders":
            self._submit(body)
            return
        parts = route.strip("/").split("/")
        if len(parts) == 3 and parts[0] == "orders" and parts[2] in ("approve", "reject"):
            self._decide(parts[1], parts[2], body)
            return
        self._send(404, {"error": "unknown route", "route": route})

    # -- the routes -------------------------------------------------------- #

    def _health(self) -> dict[str, Any]:
        control = self._server.control_plane
        return {
            "status": "ok",
            "public_key": control.signer.public_key_b64,
            "key_id": control.signer.key_id,
            "pending_orders": len(control.pending),
            "decisions": len(self._server.decisions),
        }

    def _quotas(self, query: dict[str, list[str]]) -> dict[str, Any]:
        agent_id = (query.get("agent_id") or [""])[0]
        task_id = (query.get("task_id") or [""])[0]
        snapshot = self._server.control_plane.quotas.snapshot(agent_id, task_id)
        return {"agent_id": agent_id, "task_id": task_id, "quotas": snapshot}

    def _orders(self) -> dict[str, Any]:
        control = self._server.control_plane
        pending = [
            {
                "order_id": pending.order.id,
                "task_id": pending.order.task_id,
                "agent_id": pending.order.agent_id,
                "action": pending.order.action.value,
                "target": pending.order.target,
                "args": pending.order.args,
                "risk": pending.decision.risk.value,
                "rule_id": pending.decision.rule_id,
                "reason": pending.decision.reason,
                "queued_at": pending.queued_at,
            }
            for pending in control.pending.values()
        ]
        return {"pending": pending, "decisions": list(self._server.decisions)}

    def _verify(self) -> dict[str, Any]:
        report = self._server.control_plane.journal.verify()
        return {
            "ok": report.ok,
            "entries_checked": report.entries_checked,
            "broken_index": report.broken_index,
            "reason": report.reason,
        }

    def _submit(self, body: dict[str, Any]) -> None:
        if not self._authorised(self._server.agent_key):
            self._send(
                401,
                {
                    "error": "unauthorised",
                    "detail": "the agent must present the agent key as a bearer token",
                },
            )
            return
        missing = [
            name
            for name in ("task_id", "agent_id", "action", "target")
            if not body.get(name)
        ]
        if missing:
            self._send(400, {"error": "missing fields", "fields": missing})
            return
        raw_action = str(body["action"])
        try:
            action = Action(raw_action)
        except ValueError:
            self._send(
                400,
                {
                    "error": "unknown action",
                    "action": raw_action,
                    "allowed": [item.value for item in Action],
                },
            )
            return
        order = Order.new(
            task_id=str(body["task_id"]),
            agent_id=str(body["agent_id"]),
            action=action,
            target=str(body["target"]),
            args=dict(body.get("args") or {}),
            justification=str(body.get("justification") or ""),
        )
        decision = self._server.control_plane.submit(order)
        payload = decision.to_dict()
        self._server.decisions.append(payload)
        status = 201
        if decision.verdict.value == "refused":
            status = 403
        self._send(status, payload)

    def _decide(self, order_id: str, what: str, body: dict[str, Any]) -> None:
        if not self._authorised(self._server.operator_key):
            self._send(
                401,
                {
                    "error": "unauthorised",
                    "detail": "a human must present the operator key as a bearer token",
                },
            )
            return
        approver = str(body.get("approver") or "operator")
        reason = str(body.get("reason") or "")
        control = self._server.control_plane
        if what == "approve":
            decision = control.approve(order_id, approver, reason)
        else:
            decision = control.reject(order_id, approver, reason)
        if decision is None:
            self._send(404, {"error": "unknown or expired order", "order_id": order_id})
            return
        payload = decision.to_dict()
        self._server.decisions.append(payload)
        self._send(200, payload)


def build_server(
    *,
    host: str = "127.0.0.1",
    port: int = 8000,
    journal_path: str | Path = "journal.jsonl",
    signing_key_path: str | Path | None = None,
    agent_key: str | None = None,
    operator_key: str | None = None,
    quota_limits: "QuotaLimits | None" = None,
    clock: Any = None,
) -> ControlPlaneServer:
    """Build the server (without starting it)."""
    journal = Journal(journal_path)
    if signing_key_path:
        signer = Signer(key_path=signing_key_path)
    else:
        seed, _ = generate_keypair()
        signer = Signer(seed=seed)
    quotas = QuotaManager(limits=quota_limits, clock=clock)
    control_plane = ControlPlane(
        journal=journal,
        signer=signer,
        quotas=quotas,
        policy=default_policy(),
        clock=clock if clock is not None else time.time,
    )
    return ControlPlaneServer(
        (host, port),
        control_plane,
        agent_key=agent_key,
        operator_key=operator_key,
    )


def serve(
    *,
    host: str = "127.0.0.1",
    port: int = 8000,
    journal_path: str | Path = "journal.jsonl",
    signing_key_path: str | Path | None = None,
    agent_key: str | None = None,
    operator_key: str | None = None,
    quota_limits: "QuotaLimits | None" = None,
) -> None:
    """Run the server until it is interrupted."""
    server = build_server(
        host=host,
        port=port,
        journal_path=journal_path,
        signing_key_path=signing_key_path,
        agent_key=agent_key,
        operator_key=operator_key,
        quota_limits=quota_limits,
    )
    print(f"signedorders is listening on http://{host}:{server.server_port}")
    print(f"  public key: {server.control_plane.signer.public_key_b64}")
    print(f"  journal:    {journal_path}")
    if not agent_key:
        print("  WARNING: no agent key configured - POST /orders is refused")
    if not operator_key:
        print("  WARNING: no operator key configured - the approvals are refused")
    try:
        server.serve_forever()
    except KeyboardInterrupt:  # pragma: no cover - interactive
        pass
    finally:
        server.server_close()
