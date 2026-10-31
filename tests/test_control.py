"""The control plane — the whole loop, from the request to the execution."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from signedorders.control import ControlPlane, ExecutionRefused
from signedorders.journal import Journal
from signedorders.models import Action, Approval, Order, Risk, Verdict
from signedorders.quotas import FakeClock, QuotaLimits, QuotaManager
from signedorders.sandbox import RecordingBackend, SandboxResult
from signedorders.signer import Signer, generate_keypair, verify


@pytest.fixture()
def clock() -> FakeClock:
    return FakeClock(1_700_000_000.0)


@pytest.fixture()
def signer() -> Signer:
    """A key generated for the test — never a real key."""
    seed, _ = generate_keypair()
    return Signer(seed=seed)


@pytest.fixture()
def plane(tmp_path: Path, signer: Signer, clock: FakeClock) -> ControlPlane:
    return ControlPlane(
        journal=Journal(tmp_path / "journal.jsonl"),
        signer=signer,
        quotas=QuotaManager(
            limits=QuotaLimits(orders_per_window=10, window_seconds=60.0),
            clock=clock,
        ),
        clock=clock,
    )


def read_order(action: Action = Action.READ, target: str = "sandbox://sb-42:/app/main.py",
               **args: str) -> Order:
    return Order.new(
        task_id="task-1",
        agent_id="agent-1",
        action=action,
        target=target,
        args=dict(args),
    )


# --------------------------------------------------------------------------- #
# The verdicts
# --------------------------------------------------------------------------- #


def test_a_low_risk_order_is_signed_and_executable(plane):
    decision = plane.submit(read_order())
    assert decision.verdict is Verdict.AUTO_EXECUTE
    assert decision.risk is Risk.LOW
    assert decision.signature is not None
    assert decision.public_key is not None


def test_the_signature_verifies_with_the_public_key_only(plane):
    decision = plane.submit(read_order())
    from signedorders.signer import SignedEnvelope

    envelope = SignedEnvelope(
        algorithm="ed25519",
        public_key=decision.public_key or "",
        signature=decision.signature or "",
        key_id="",
    )
    assert verify(envelope, decision.canonical()) is True


def test_the_signed_payload_carries_the_rule_and_the_quota_snapshot(plane):
    decision = plane.submit(read_order())
    payload = decision.canonical()
    assert payload["rule_id"] == "allow.sandbox_read"
    assert payload["risk"] == "low"
    assert payload["quota_snapshot"]["orders_per_window"] == 10


def test_a_forbidden_order_is_refused(plane):
    decision = plane.submit(read_order(Action.READ, "host://etc/shadow"))
    assert decision.verdict is Verdict.REFUSED
    assert decision.risk is Risk.FORBIDDEN
    assert decision.signature is None


def test_a_high_risk_order_waits_for_a_human(plane):
    decision = plane.submit(read_order(Action.DELETE, "sandbox://sb-42:/srv/data", command="rm -rf /srv/data"))
    assert decision.verdict is Verdict.QUEUED_FOR_HUMAN
    assert decision.risk is Risk.HIGH
    assert decision.order.id in plane.pending


def test_the_quota_refuses_the_order_when_it_is_broken(plane, clock):
    for _ in range(10):
        plane.submit(read_order())
    decision = plane.submit(read_order())
    assert decision.verdict is Verdict.REFUSED
    assert decision.rule_id == "quota.exceeded"
    assert "orders_per_window" in decision.reason


def test_the_quota_refuses_even_a_low_risk_order(plane):
    """A quota is not overridable by the risk level."""
    plane.quotas.limits = QuotaLimits(orders_per_window=0, window_seconds=60.0)
    decision = plane.submit(read_order())
    assert decision.verdict is Verdict.REFUSED
    assert decision.rule_id == "quota.exceeded"


def test_every_decision_is_journalled(plane):
    plane.submit(read_order())
    entries = plane.journal.entries()
    assert len(entries) == 1
    record = entries[0].record
    assert record["kind"] == "decision"
    assert record["rule_id"] == "allow.sandbox_read"
    assert record["trace"]["rule_id"] == "allow.sandbox_read"
    assert record["trace"]["inputs"]["action"] == "read"
    assert record["trace"]["outputs"]["risk"] == "low"


# --------------------------------------------------------------------------- #
# The human queue
# --------------------------------------------------------------------------- #


def test_a_queued_order_cannot_be_executed(plane, tmp_path):
    decision = plane.submit(read_order(Action.DELETE, "sandbox://sb-42:/srv/data", command="rm -rf /srv/data"))
    with pytest.raises(ExecutionRefused):
        plane.execute(decision, RecordingBackend(), tmp_path)


def test_a_signed_approval_unlocks_the_order(plane, tmp_path):
    decision = plane.submit(read_order(Action.DELETE, "sandbox://sb-42:/srv/data", command="rm -rf /srv/data"))
    approver_seed, _ = generate_keypair()
    approver = Signer(seed=approver_seed)

    approved = plane.approve(decision.order.id, "alice", "the data is a copy", approver)
    assert approved is not None
    assert approved.verdict is Verdict.APPROVED
    assert decision.order.id not in plane.pending

    result = plane.execute(approved, RecordingBackend(), tmp_path)
    assert result.exit_code == 0


def test_the_approval_is_signed_and_verifiable(plane):
    decision = plane.submit(read_order(Action.DELETE, "sandbox://sb-42:/srv/data", command="rm -rf /srv/data"))
    approver_seed, _ = generate_keypair()
    approver = Signer(seed=approver_seed)
    plane.approve(decision.order.id, "alice", "ok", approver)

    approval = plane.approvals[decision.order.id]
    assert approval.signature
    assert approval.public_key == approver.public_key_b64
    assert plane.verify_approval(approval) is True


def test_a_forged_approval_is_refused(plane, tmp_path):
    decision = plane.submit(read_order(Action.DELETE, "sandbox://sb-42:/srv/data", command="rm -rf /srv/data"))
    approver_seed, _ = generate_keypair()
    approver = Signer(seed=approver_seed)
    approved = plane.approve(decision.order.id, "alice", "ok", approver)

    # somebody rewrites the approval after the fact
    original = plane.approvals[decision.order.id]
    plane.approvals[decision.order.id] = Approval(
        order_id=original.order_id,
        approver="mallory",
        decision="approve",
        reason="ok",
        signature=original.signature,
        public_key=original.public_key,
        created_at=original.created_at,
    )
    with pytest.raises(ExecutionRefused):
        plane.check_executable(approved)


def test_a_rejection_cannot_be_executed(plane, tmp_path):
    decision = plane.submit(read_order(Action.DELETE, "sandbox://sb-42:/srv/data", command="rm -rf /srv/data"))
    rejected = plane.reject(decision.order.id, "bob", "no way")
    assert rejected is not None
    assert rejected.verdict is Verdict.REJECTED
    with pytest.raises(ExecutionRefused):
        plane.check_executable(rejected)


def test_a_human_can_refuse(plane):
    decision = plane.submit(read_order(Action.DELETE, "sandbox://sb-42:/srv/data", command="rm -rf /srv/data"))
    rejected = plane.reject(decision.order.id, "bob", "no way")
    assert rejected is not None
    assert rejected.verdict is Verdict.REJECTED
    assert plane.pending == {}


def test_a_queued_order_expires_when_nobody_answers(plane, clock):
    decision = plane.submit(read_order(Action.DELETE, "sandbox://sb-42:/srv/data", command="rm -rf /srv/data"))
    clock.advance(1000.0)
    expired = plane.expire()
    assert len(expired) == 1
    assert expired[0].verdict is Verdict.EXPIRED
    assert expired[0].order.id == decision.order.id
    assert plane.pending == {}


def test_an_order_that_did_not_wait_does_not_expire(plane, clock):
    plane.submit(read_order())
    clock.advance(10_000.0)
    assert plane.expire() == []


def test_approving_an_unknown_order_returns_nothing(plane):
    assert plane.approve("so_does_not_exist", "alice") is None


# --------------------------------------------------------------------------- #
# The execution
# --------------------------------------------------------------------------- #


def test_the_execution_is_journalled_with_its_output(plane, tmp_path):
    backend = RecordingBackend(result=SandboxResult(exit_code=0, stdout="1 passed"))
    decision = plane.submit(read_order(Action.EXEC, "sandbox://sb-42:/app/run.sh",
                                       command="pytest -q"))
    plane.execute(decision, backend, tmp_path)

    kinds = [entry.kind for entry in plane.journal.entries()]
    assert kinds == ["decision", "execution"]
    execution = plane.journal.entries()[1].record
    assert execution["exit_code"] == 0
    assert "1 passed" in execution["stdout"]
    assert execution["sandbox_id"]


def test_the_sandbox_is_opened_and_closed_around_the_execution(plane, tmp_path):
    backend = RecordingBackend()
    decision = plane.submit(read_order(Action.EXEC, "sandbox://sb-42:/app/run.sh",
                                       command="pytest -q"))
    plane.execute(decision, backend, tmp_path)
    assert plane.quotas.snapshot("agent-1", "task-1")["task_sandboxes_open"] == 0


def test_the_command_reaches_the_sandbox(plane, tmp_path):
    backend = RecordingBackend()
    decision = plane.submit(read_order(Action.EXEC, "sandbox://sb-42:/app/run.sh",
                                       command="pytest -q"))
    plane.execute(decision, backend, tmp_path)
    assert backend.calls[0]["command"] == ["pytest -q"]


def test_an_order_without_a_command_cannot_be_executed(plane, tmp_path):
    decision = plane.submit(read_order(Action.READ))
    with pytest.raises(ExecutionRefused):
        plane.execute(decision, RecordingBackend(), tmp_path)


def test_a_refused_order_can_never_be_executed(plane, tmp_path):
    decision = plane.submit(read_order(Action.READ, "host://etc/shadow"))
    with pytest.raises(ExecutionRefused):
        plane.execute(decision, RecordingBackend(), tmp_path)


def test_a_tampered_signature_is_refused(plane, tmp_path):
    """The executor re-checks the signature: a forged order never runs."""
    decision = plane.submit(read_order())
    forged = type(decision)(
        order=decision.order,
        risk=decision.risk,
        verdict=decision.verdict,
        rule_id=decision.rule_id,
        reason=decision.reason,
        trace=decision.trace,
        signature="A" * 86 + "=",
        public_key=decision.public_key,
        quota_snapshot=decision.quota_snapshot,
    )
    with pytest.raises(ExecutionRefused):
        plane.check_executable(forged)


# --------------------------------------------------------------------------- #
# The journal of a whole task
# --------------------------------------------------------------------------- #


def test_the_journal_of_a_task_can_be_verified_afterwards(plane, tmp_path):
    plane.submit(read_order())
    plane.submit(read_order(Action.DELETE, "sandbox://sb-42:/srv/data", command="rm -rf /srv/data"))
    report = plane.journal.verify()
    assert report.ok is True
    assert report.entries_checked == 2


def test_the_journal_is_json_lines(plane):
    plane.submit(read_order())
    lines = plane.journal.path.read_text(encoding="utf-8").strip().split("\n")
    assert len(lines) == 1
    record = json.loads(lines[0])
    assert record["kind"] == "decision"
    assert record["hash"]
    assert record["previous_hash"]
