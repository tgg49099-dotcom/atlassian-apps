"""The signer — sign, verify, and refuse a forged signature."""

from __future__ import annotations

from pathlib import Path

import pytest

from signedorders.signer import (
    SignedEnvelope,
    Signer,
    generate_keypair,
    key_id,
    sign_bytes,
    verify,
    verify_raw,
)


@pytest.fixture()
def keypair() -> tuple[bytes, bytes]:
    """A key generated for the test — never a real key."""
    return generate_keypair()


def test_the_generated_key_is_32_bytes(keypair):
    seed, public = keypair
    assert len(seed) == 32
    assert len(public) == 32


def test_two_keypairs_are_different():
    assert generate_keypair()[0] != generate_keypair()[0]


def test_a_signed_payload_verifies(keypair):
    seed, _ = keypair
    signer = Signer(seed=seed)
    payload = {"action": "read", "target": "sandbox://sb-42:/app/main.py"}
    envelope = signer.sign(payload)
    assert verify(envelope, payload) is True


def test_a_modified_payload_does_not_verify(keypair):
    seed, _ = keypair
    signer = Signer(seed=seed)
    payload = {"action": "read", "target": "sandbox://sb-42:/app/main.py"}
    envelope = signer.sign(payload)
    assert verify(envelope, {**payload, "action": "delete"}) is False


def test_a_signature_is_bound_to_its_own_public_key(keypair):
    """The envelope carries the key that verifies it: swap it and it fails."""
    seed, _ = keypair
    other_seed, _ = generate_keypair()
    signer = Signer(seed=seed)
    other = Signer(seed=other_seed)
    payload = {"action": "read"}
    envelope = signer.sign(payload)
    assert verify(envelope, payload) is True

    swapped = SignedEnvelope(
        algorithm=envelope.algorithm,
        public_key=other.public_key_b64,
        signature=envelope.signature,
        key_id=envelope.key_id,
    )
    assert verify(swapped, payload) is False


def test_the_envelope_is_serialisable(keypair):
    seed, _ = keypair
    signer = Signer(seed=seed)
    envelope = signer.sign({"a": 1})
    restored = SignedEnvelope.from_dict(envelope.to_dict())
    assert restored == envelope


def test_the_key_id_identifies_the_public_key(keypair):
    seed, public = keypair
    signer = Signer(seed=seed)
    assert signer.key_id == key_id(public)
    assert len(signer.key_id) == 16


def test_a_malformed_envelope_is_refused_not_raised():
    envelope = SignedEnvelope(
        algorithm="ed25519", public_key="not base64 !", signature="==", key_id="x"
    )
    assert verify(envelope, {"a": 1}) is False


def test_an_empty_signature_is_refused():
    envelope = SignedEnvelope(
        algorithm="ed25519", public_key="AAAA", signature="", key_id="x"
    )
    assert verify(envelope, {"a": 1}) is False


def test_the_signature_is_deterministic(keypair):
    seed, _ = keypair
    signer = Signer(seed=seed)
    payload = {"action": "read"}
    assert signer.sign(payload).signature == signer.sign(payload).signature


def test_the_raw_helpers_agree_with_the_envelope(keypair):
    seed, public = keypair
    signer = Signer(seed=seed)
    payload = {"a": 1}
    envelope = signer.sign(payload)
    assert verify_raw(envelope.public_key, envelope.signature, b'{"a":1}') is True
    assert verify_raw(envelope.public_key, envelope.signature, b'{"a":2}') is False


def test_sign_bytes_returns_64_bytes(keypair):
    seed, _ = keypair
    assert len(sign_bytes(seed, b"hello")) == 64


# --------------------------------------------------------------------------- #
# The key on the host
# --------------------------------------------------------------------------- #


def test_the_key_is_generated_on_the_host_with_the_right_permissions(tmp_path: Path):
    path = tmp_path / "signing.key"
    signer = Signer.generate(path)
    assert path.exists()
    assert path.stat().st_mode & 0o777 == 0o600
    assert len(path.read_bytes()) == 32
    assert signer.public_key_b64


def test_a_signer_reads_the_key_back(tmp_path: Path):
    path = tmp_path / "signing.key"
    first = Signer.generate(path)
    second = Signer(key_path=path)
    assert first.public_key_b64 == second.public_key_b64
    payload = {"a": 1}
    assert verify(second.sign(payload), payload) is True


def test_a_key_file_of_the_wrong_size_is_refused(tmp_path: Path):
    path = tmp_path / "signing.key"
    path.write_bytes(b"too short")
    with pytest.raises(ValueError):
        Signer(key_path=path)


def test_a_signer_without_a_key_is_refused():
    with pytest.raises(ValueError):
        Signer()
