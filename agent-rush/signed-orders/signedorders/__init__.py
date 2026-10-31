"""Signed Orders — a containment-first control plane for autonomous agents.

The agent never gets a shell.  It can only *request an order*; the control
plane classifies the risk with auditable rules, enforces the quotas, signs
the order (Ed25519) or queues it for a human, and a separate executor runs it
inside a fresh, isolated sandbox.

Public entry points
-------------------
``signedorders.policy``    risk classification (named rules, no LLM in the loop)
``signedorders.quotas``    per-agent sliding window and per-task budgets
``signedorders.journal``   append-only, hash-chained JSONL journal
``signedorders.signer``    Ed25519 signatures (pure Python, RFC 8032)
``signedorders.control``   the control plane: request -> verdict -> sign/queue
``signedorders.sandbox``   the manifest and the two isolation backends
``signedorders.vultr``     the Vultr Serverless Inference client (needs a key)
``signedorders.replay``    read a journal back, in order
``signedorders.preflight`` secret scanner, run before every commit

The core has **no third-party dependency**: ``python3 -m signedorders verify
journal.jsonl`` works on a bare Python 3.10+.
"""

__version__ = "0.2.0"

__all__ = [
    "canonical",
    "config",
    "control",
    "ed25519",
    "journal",
    "models",
    "policy",
    "preflight",
    "quotas",
    "sandbox",
    "signer",
    "vultr",
]
