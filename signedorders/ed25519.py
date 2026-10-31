"""Ed25519 (RFC 8032) in pure Python — no dependency, no wheel, no excuse.

Why this file exists
--------------------
The whole point of Signed Orders is that **a third party can check our story
without trusting us and without owning a secret**.  If the verification needs a
compiled C extension, the demo needs ``pip install``, and the demo needs a
network.  This module is therefore a self-contained implementation of RFC 8032
(Ed25519, PureEdDSA) in ~150 lines of readable Python:

* it only needs ``hashlib`` (from the standard library);
* the same bytes are produced as with ``cryptography`` / ``libsodium``;
* ``tests/test_ed25519.py`` cross-checks every operation against
  ``cryptography`` when it is installed, and against the RFC 8032 test vector
  otherwise.

It is **not** a toy: it is used to sign the orders and to verify the journal
whenever the optional ``cryptography`` dependency is not installed.

Implementation notes
--------------------
* Curve: twisted Edwards ``-x^2 + y^2 = 1 + d x^2 y^2`` over ``GF(p)``,
  ``p = 2^255 - 19``, base point ``B``, order ``l = 2^252 + 27742…``.
* Points are handled in **extended homogeneous coordinates** ``(X, Y, Z, T)``
  with ``x = X/Z``, ``y = Y/Z`` and ``x*y = T/Z`` — the same representation as
  the reference implementation of RFC 8032.
* Signing is deterministic (RFC 8032 §5.1.6): no random nonce, so the same
  message always yields the same signature.  That is what makes a journal
  replayable.
"""

from __future__ import annotations

import hashlib
import os

__all__ = [
    "SEED_SIZE",
    "PUBLIC_KEY_SIZE",
    "SIGNATURE_SIZE",
    "generate_seed",
    "public_key_from_seed",
    "sign",
    "verify",
    "is_valid_public_key",
]

#: Sizes, in bytes, as fixed by RFC 8032.
SEED_SIZE = 32
PUBLIC_KEY_SIZE = 32
SIGNATURE_SIZE = 64

# --------------------------------------------------------------------------- #
# Curve parameters
# --------------------------------------------------------------------------- #

_P = 2**255 - 19
_L = 2**252 + 27742317777372353535851937790883648493
_D = -121665 * pow(121666, _P - 2, _P) % _P
_I = pow(2, (_P - 1) // 4, _P)  # sqrt(-1)


def _sha512(data: bytes) -> bytes:
    return hashlib.sha512(data).digest()


def _recover_x(y: int, sign: int) -> int | None:
    """Recover the ``x`` coordinate of a point from its ``y`` (``None`` if none)."""
    if y >= _P:
        return None
    x2 = (y * y - 1) * pow(_D * y * y + 1, _P - 2, _P) % _P
    if x2 == 0:
        return None if sign else 0
    x = pow(x2, (_P + 3) // 8, _P)
    if (x * x - x2) % _P != 0:
        x = x * _I % _P
    if (x * x - x2) % _P != 0:
        return None
    if (x & 1) != sign:
        x = _P - x
    return x


# The base point B (y = 4/5).
_BY = 4 * pow(5, _P - 2, _P) % _P
_BX = _recover_x(_BY, 0)
assert _BX is not None
#: Base point in extended coordinates ``(X, Y, Z, T)``.
_B = (_BX, _BY, 1, _BX * _BY % _P)
#: Neutral element.
_IDENTITY = (0, 1, 1, 0)


def _point_add(p: tuple[int, int, int, int], q: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    """Add two points in extended coordinates (the ``add-2008-hwcd-3`` formulas)."""
    a = (p[1] - p[0]) * (q[1] - q[0]) % _P
    b = (p[1] + p[0]) * (q[1] + q[0]) % _P
    c = 2 * p[3] * q[3] * _D % _P
    d = 2 * p[2] * q[2] % _P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % _P, g * h % _P, f * g % _P, e * h % _P)


def _point_mul(scalar: int, point: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    """Multiply ``point`` by ``scalar`` (double-and-add, constant time in shape)."""
    result = _IDENTITY
    while scalar > 0:
        if scalar & 1:
            result = _point_add(result, point)
        point = _point_add(point, point)
        scalar >>= 1
    return result


def _point_compress(point: tuple[int, int, int, int]) -> bytes:
    """Encode a point as 32 bytes (little-endian ``y`` with the sign of ``x``)."""
    x, y, z = point[0], point[1], point[2]
    inv_z = pow(z, _P - 2, _P)
    x = x * inv_z % _P
    y = y * inv_z % _P
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def _point_decompress(data: bytes) -> tuple[int, int, int, int] | None:
    """Decode 32 bytes into a point (``None`` if the encoding is invalid)."""
    if len(data) != PUBLIC_KEY_SIZE:
        return None
    value = int.from_bytes(data, "little")
    sign = value >> 255
    y = value & ((1 << 255) - 1)
    x = _recover_x(y, sign)
    if x is None:
        return None
    return (x, y, 1, x * y % _P)


def _point_equal(p: tuple[int, int, int, int], q: tuple[int, int, int, int]) -> bool:
    if (p[0] * q[2] - q[0] * p[2]) % _P != 0:
        return False
    return (p[1] * q[2] - q[1] * p[2]) % _P == 0


def _secret_expand(seed: bytes) -> tuple[int, bytes]:
    """Split a 32-byte seed into the signing scalar and the nonce prefix."""
    if len(seed) != SEED_SIZE:
        raise ValueError(f"the seed must be {SEED_SIZE} bytes, got {len(seed)}")
    digest = _sha512(seed)
    a = int.from_bytes(digest[:32], "little")
    a &= (1 << 254) - 8          # clear the low 3 bits
    a |= 1 << 254               # make it a multiple of the cofactor
    return a, digest[32:]


# --------------------------------------------------------------------------- #
# Public API
# --------------------------------------------------------------------------- #


def generate_seed() -> bytes:
    """Return 32 random bytes suitable as a private key seed."""
    return os.urandom(SEED_SIZE)


def public_key_from_seed(seed: bytes) -> bytes:
    """Return the 32-byte public key that matches the given private seed."""
    a, _ = _secret_expand(seed)
    return _point_compress(_point_mul(a, _B))


def is_valid_public_key(public_key: bytes) -> bool:
    """True when ``public_key`` is a well-formed point on the curve."""
    return _point_decompress(public_key) is not None


def sign(seed: bytes, message: bytes) -> bytes:
    """Sign ``message`` with the private ``seed``; returns 64 bytes.

    The signature is deterministic (RFC 8032): ``sign(seed, m)`` always returns
    the same bytes, which is what makes a signed order replayable.
    """
    a, prefix = _secret_expand(seed)
    public_key = _point_compress(_point_mul(a, _B))
    r = int.from_bytes(_sha512(prefix + message), "little") % _L
    big_r = _point_compress(_point_mul(r, _B))
    k = int.from_bytes(_sha512(big_r + public_key + message), "little") % _L
    s = (r + k * a) % _L
    return big_r + int.to_bytes(s, 32, "little")


def verify(public_key: bytes, message: bytes, signature: bytes) -> bool:
    """Verify a 64-byte signature; never raises, only answers True or False."""
    if len(signature) != SIGNATURE_SIZE or len(public_key) != PUBLIC_KEY_SIZE:
        return False
    point_a = _point_decompress(public_key)
    if point_a is None:
        return False
    point_r = _point_decompress(signature[:32])
    if point_r is None:
        return False
    s = int.from_bytes(signature[32:], "little")
    if s >= _L:
        return False
    k = int.from_bytes(_sha512(signature[:32] + public_key + message), "little") % _L
    left = _point_mul(s, _B)
    right = _point_add(point_r, _point_mul(k, point_a))
    return _point_equal(left, right)
