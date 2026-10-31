"""Ed25519 signatures over orders and journal records.

The private key is generated on the host, stored outside the repository
(``SIGNEDORDERS_SIGNING_KEY_PATH``, mode 0600) and never leaves the control
plane. The **public** key is committed on purpose: it lets anyone verify a
journal offline, with no secret and no server.

TODO(build-window): key rotation (``signedorders init-key --rotate``) and the
key id in the signature envelope.
"""

from __future__ import annotations

import base64
from dataclasses import dataclass
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

from .journal import canonical_json


@dataclass(frozen=True)
class SignedEnvelope:
    """A signature plus the key that produced it, in a serialisable form."""

    algorithm: str
    public_key: str
    signature: str

    def to_dict(self) -> dict[str, str]:
        return {
            "algorithm": self.algorithm,
            "public_key": self.public_key,
            "signature": self.signature,
        }


class Signer:
    """Signs canonical bytes with an Ed25519 key stored on the host."""

    def __init__(self, private_key_path: str | Path) -> None:
        self.private_key_path = Path(private_key_path)

    # -- keys -------------------------------------------------------------- #

    def generate(self) -> str:
        """Create a new private key on disk and return the matching public key.

        The private key is written with mode 0600 and never returned.
        """
        key = Ed25519PrivateKey.generate()
        self.private_key_path.parent.mkdir(parents=True, exist_ok=True)
        pem = key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
        self.private_key_path.write_bytes(pem)
        self.private_key_path.chmod(0o600)
        return self.public_key_b64()

    def _private_key(self) -> Ed25519PrivateKey:
        pem = self.private_key_path.read_bytes()
        key = serialization.load_pem_private_key(pem, password=None)
        if not isinstance(key, Ed25519PrivateKey):
            raise TypeError("the signing key is not an Ed25519 key")
        return key

    def public_key_b64(self) -> str:
        public = self._private_key().public_key()
        raw = public.public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw,
        )
        return base64.b64encode(raw).decode("ascii")

    # -- signing ----------------------------------------------------------- #

    def sign(self, payload: dict) -> SignedEnvelope:
        """Sign a dict (canonically serialised) with the private key."""
        signature = self._private_key().sign(canonical_json(payload))
        return SignedEnvelope(
            algorithm="ed25519",
            public_key=self.public_key_b64(),
            signature=base64.b64encode(signature).decode("ascii"),
        )


def verify(envelope: SignedEnvelope, payload: dict) -> bool:
    """Verify a signature using **only** the public key it carries.

    This is the function a third party runs: no key, no server, no trust in the
    vendor.
    """
    try:
        public = Ed25519PublicKey.from_public_bytes(
            base64.b64decode(envelope.public_key)
        )
        public.verify(base64.b64decode(envelope.signature), canonical_json(payload))
    except (InvalidSignature, ValueError, TypeError):
        return False
    return True


__all__ = ["SignedEnvelope", "Signer", "verify"]
