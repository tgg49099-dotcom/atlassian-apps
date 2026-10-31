"""The canonical form — the exact bytes that are hashed and signed.

Everything that is signed or hashed in Signed Orders goes through
:func:`canonical_json`.  The rules are deliberately boring, because boring is
what makes it reproducible:

* keys sorted;
* no insignificant whitespace;
* UTF-8, and non-ASCII characters are **not** escaped;
* ``NaN`` / ``Infinity`` are rejected (they are not valid JSON and would make
  two different implementations disagree).

Two runs, two machines, two Python versions produce the same bytes — that is
the property the whole journal depends on.
"""

from __future__ import annotations

import json
from typing import Any

__all__ = ["canonical_json", "canonical_text", "stable_id"]


def canonical_json(payload: Any) -> bytes:
    """Return the canonical UTF-8 bytes of ``payload``."""
    return json.dumps(
        payload,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")


def canonical_text(payload: Any) -> str:
    """Return the canonical JSON as text (handy for logs and error messages)."""
    return canonical_json(payload).decode("utf-8")


def stable_id(prefix: str, payload: Any, length: int = 16) -> str:
    """Return a short, deterministic identifier derived from ``payload``.

    The identifier of an order is derived from its content, so two control
    planes that receive the same request compute the same id — which makes
    the journal comparable between two runs.
    """
    import hashlib

    digest = hashlib.sha256(canonical_json(payload)).hexdigest()
    return f"{prefix}_{digest[:length]}"
