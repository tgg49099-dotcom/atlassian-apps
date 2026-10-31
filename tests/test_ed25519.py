"""The Ed25519 implementation — RFC 8032, and hostile inputs."""

from __future__ import annotations

import hashlib
import os

import pytest

from signedorders import ed25519

try:  # the cross-check is optional: the core has no dependency
    import cryptography  # noqa: F401

    HAS_CRYPTOGRAPHY = True
except ImportError:  # pragma: no cover - depends on the host
    HAS_CRYPTOGRAPHY = False

# The first test vector of RFC 8032, section 7.1 (TEST 1).
RFC8032_SEED = bytes.fromhex(
    "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
)
RFC8032_PUBLIC = bytes.fromhex(
    "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
)
RFC8032_SIGNATURE = bytes.fromhex(
    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555"
    "fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
)


def test_rfc8032_vector_1_public_key():
    assert ed25519.public_key_from_seed(RFC8032_SEED) == RFC8032_PUBLIC


def test_rfc8032_vector_1_signature():
    assert ed25519.sign(RFC8032_SEED, b"") == RFC8032_SIGNATURE


def test_rfc8032_vector_1_verifies():
    assert ed25519.verify(RFC8032_PUBLIC, b"", RFC8032_SIGNATURE) is True


def test_seed_of_the_wrong_length_is_refused():
    with pytest.raises(ValueError):
        ed25519.public_key_from_seed(b"too short")


def test_signature_is_deterministic():
    seed = os.urandom(32)
    message = b"the same message"
    assert ed25519.sign(seed, message) == ed25519.sign(seed, message)


def test_a_modified_message_does_not_verify():
    seed = os.urandom(32)
    public = ed25519.public_key_from_seed(seed)
    signature = ed25519.sign(seed, b"pay 10 euros")
    assert ed25519.verify(public, b"pay 10 euros", signature) is True
    assert ed25519.verify(public, b"pay 1000 euros", signature) is False


def test_a_modified_signature_does_not_verify():
    seed = os.urandom(32)
    public = ed25519.public_key_from_seed(seed)
    signature = bytearray(ed25519.sign(seed, b"hello"))
    signature[0] ^= 0x01
    assert ed25519.verify(public, b"hello", bytes(signature)) is False


def test_another_key_does_not_verify():
    seed = os.urandom(32)
    other = os.urandom(32)
    public = ed25519.public_key_from_seed(other)
    signature = ed25519.sign(seed, b"hello")
    assert ed25519.verify(public, b"hello", signature) is False


def test_a_truncated_signature_is_refused():
    seed = os.urandom(32)
    public = ed25519.public_key_from_seed(seed)
    signature = ed25519.sign(seed, b"hello")
    assert ed25519.verify(public, b"hello", signature[:-1]) is False
    assert ed25519.verify(public, b"hello", signature + b"\x00") is False


def test_a_public_key_that_is_not_a_point_is_refused():
    assert ed25519.verify(b"\xff" * 32, b"hello", b"\x00" * 64) is False


def test_an_s_value_above_the_order_is_refused():
    seed = os.urandom(32)
    public = ed25519.public_key_from_seed(seed)
    signature = ed25519.sign(seed, b"hello")
    forged = signature[:32] + (ed25519._L + 1).to_bytes(32, "little")
    assert ed25519.verify(public, b"hello", forged) is False


def test_public_key_is_valid():
    seed = os.urandom(32)
    assert ed25519.is_valid_public_key(ed25519.public_key_from_seed(seed)) is True
    assert ed25519.is_valid_public_key(b"\xff" * 32) is False


def test_generated_seeds_are_different():
    assert ed25519.generate_seed() != ed25519.generate_seed()


@pytest.mark.skipif(not HAS_CRYPTOGRAPHY, reason="cryptography is not installed")
def test_matches_the_cryptography_library():
    """The pure implementation must be byte-identical to the reference one."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

    for _ in range(5):
        seed = os.urandom(32)
        message = os.urandom(37)
        reference = Ed25519PrivateKey.from_private_bytes(seed)
        reference_public = reference.public_key().public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw,
        )
        reference_signature = reference.sign(message)
        assert ed25519.public_key_from_seed(seed) == reference_public
        assert ed25519.sign(seed, message) == reference_signature
        assert ed25519.verify(reference_public, message, reference_signature) is True


def test_the_public_key_is_derived_from_the_seed_only():
    seed = os.urandom(32)
    first = ed25519.public_key_from_seed(seed)
    second = ed25519.public_key_from_seed(seed)
    assert first == second
    assert hashlib.sha256(first).hexdigest() == hashlib.sha256(second).hexdigest()
