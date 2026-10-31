"""Ed25519 signatures over the orders, the approvals and the journal.

The **private** key lives on the machine that runs the control plane and never
enters the repository.  The **public** key is committed on purpose: it lets
anybody verify a journal offline, with no secret and no server.

The implementation is the dependency-free one in :mod:`signedorders.ed25519`
(RFC 8032), so ``python3 -m signedorders verify journal.jsonl`` works on a
bare Python 3.10+ with **nothing installed**.  When ``cryptography`` happens
to be installed, the tests cross-check the two implementations byte for byte.

Deterministic signatures (RFC 8032 §5.1.6) are a feature here: the same order
always produces the same signature, so a journal can be replayed and compared.
"""

from __future__ import annotations

import base64
import hashlib
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from . import ed25519
from .canonical import canonical_json

__all__ = [
    "SignedEnvelope",
    "Signer",
    "generate_keypair",
    "key_id",
    "sign_bytes",
    "verify_bytes",
]


def _b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def _unb64(text: str) -> bytes:
    return base64.b64decode(text.encode("ascii"), validate=True)


def key_id(public_key: bytes) -> str:
    """A short, stable identifier of a public key (the first 16 hex of SHA-256)."""
    return hashlib.sha256(public_key).hexdigest()[:16]


def generate_keypair() -> tuple[bytes, bytes]:
    """Return ``(seed, public_key)``, both 32 bytes.

    The seed is the private key: it must never be written in the repository,
    never logged, and never sent to a sandbox.
    """
    seed = ed25519.generate_seed()
    return seed, ed25519.public_key_from_seed(seed)


def sign_bytes(seed: bytes, message: bytes) -> bytes:
    """Sign raw bytes with the private ``seed``."""
    return ed25519.sign(seed, message)


def verify_bytes(public_key: bytes, message: bytes, signature: bytes) -> bool:
    """Verify a raw signature; never raises, only answers True or False."""
    return ed25519.verify(public_key, message, signature)


@dataclass(frozen=True)
class SignedEnvelope:
    """A signature plus the public key that verifies it, in a serialisable form."""

    algorithm: str
    public_key: str
    signature: str
    key_id: str

    def to_dict(self) -> dict[str, str]:
        return {
            "algorithm": self.algorithm,
            "public_key": self.public_key,
            "signature": self.signature,
            "key_id": self.key_id,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "SignedEnvelope":
        return cls(
            algorithm=str(data.get("algorithm", "")),
            public_key=str(data.get("public_key", "")),
            signature=str(data.get("signature", "")),
            key_id=str(data.get("key_id", "")),
        )


class Signer:
    """Signs canonical payloads with an Ed25519 key.

    The key can be given as raw bytes (tests) or as a path on the host (the
    control plane).  The file is created with mode 0600 and never contains
    anything but the seed.
    """

    def __init__(self, seed: bytes | None = None, key_path: str | Path | None = None) -> None:
        if seed is None and key_path is None:
            raise ValueError("a Signer needs a seed or a key path")
        self.key_path = Path(key_path) if key_path else None
        self._seed = seed
        if seed is None:
            self._seed = self._read_key()

    # -- keys -------------------------------------------------------------- #

    @classmethod
    def generate(cls, key_path: str | Path) -> "Signer":
        """Create a new private key on disk (mode 0600) and return the signer."""
        seed, _ = generate_keypair()
        path = Path(key_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(seed)
        path.chmod(0o600)
        return cls(seed=seed, key_path=path)

    def _read_key(self) -> bytes:
        assert self.key_path is not None
        raw = self.key_path.read_bytes()
        if len(raw) != ed25519.SEED_SIZE:
            raise ValueError(
                f"the signing key file must hold {ed25519.SEED_SIZE} bytes, "
                f"got {len(raw)}"
            )
        return raw

    @property
    def public_key(self) -> bytes:
        return ed25519.public_key_from_seed(self._seed or b"")

    @property
    def public_key_b64(self) -> str:
        return _b64(self.public_key)

    @property
    def key_id(self) -> str:
        return key_id(self.public_key)

    # -- signing ----------------------------------------------------------- #

    def sign(self, payload: dict[str, Any]) -> SignedEnvelope:
        """Sign a mapping (canonically serialised) with the private key."""
        signature = sign_bytes(self._seed or b"", canonical_json(payload))
        return SignedEnvelope(
            algorithm="ed25519",
            public_key=self.public_key_b64,
            signature=_b64(signature),
            key_id=self.key_id,
        )

    def sign_bytes(self, message: bytes) -> SignedEnvelope:
        """Sign raw bytes (used for the approval of a human)."""
        signature = sign_bytes(self._seed or b"", message)
        return SignedEnvelope(
            algorithm="ed25519",
            public_key=self.public_key_b64,
            signature=_b64(signature),
            key_id=self.key_id,
        )


def verify(envelope: SignedEnvelope, payload: dict[str, Any]) -> bool:
    """Verify a signature using **only** the public key it carries.

    This is the function a third party runs: no key, no server, no trust in
    the vendor.  It answers False — never raises — when the envelope is
    malformed.
    """
    try:
        public_key = _unb64(envelope.public_key)
        signature = _unb64(envelope.signature)
    except (ValueError, TypeError):
        return False
    if len(public_key) != ed25519.PUBLIC_KEY_SIZE:
        return False
    if len(signature) != ed25519.SIGNATURE_SIZE:
        return False
    return verify_bytes(public_key, canonical_json(payload), signature)


def verify_raw(public_key_b64: str, signature_b64: str, message: bytes) -> bool:
    """Verify a raw signature over raw bytes (the offline journal checker)."""
    try:
        public_key = _unb64(public_key_b64)
        signature = _unb64(signature_b64)
    except (ValueError, TypeError):
        return False
    return verify_bytes(public_key, message, signature)


def new_seed() -> bytes:
    """Generate a fresh private seed (32 random bytes)."""
    return os.urandom(ed25519.SEED_SIZE)
