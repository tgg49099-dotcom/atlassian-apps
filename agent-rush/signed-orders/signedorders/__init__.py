"""Signed Orders — a containment-first control plane for autonomous agents.

The agent never gets a shell. It can only *request an order*; the control plane
classifies the risk, enforces quotas, signs the order (Ed25519) or queues it for
a human, and a separate executor runs it inside a fresh, isolated sandbox.

Public entry points
-------------------
``signedorders.policy``    risk classification (auditable rules, no LLM in the loop)
``signedorders.quotas``    per-agent / per-task budgets (orders, CPU-seconds, bytes, tokens)
``signedorders.journal``   append-only, hash-chained JSONL journal
``signedorders.signer``    Ed25519 signatures over the journal
``signedorders.orders``    order lifecycle: request -> verdict -> signature/human -> execution
``signedorders.sandbox``   two interchangeable sandbox backends (Docker, Microsandbox)
``signedorders.agent``     the LLM worker loop (Vultr Serverless Inference)
``signedorders.replay``    re-run a recorded journal in a fresh sandbox and compare
``signedorders.preflight`` secret scanner, run before every commit

Status
------
This tree is the **skeleton**: the contracts, types and signatures between the
components. The implementation is written during the official build window of the
Vultr: Agent Rush Hackathon (3-8 November 2026). Every placeholder is marked
``TODO(build-window)``; nothing here pretends to work yet.
"""

__version__ = "0.1.0.dev0"

__all__ = [
    "policy",
    "quotas",
    "journal",
    "signer",
    "orders",
    "sandbox",
    "agent",
    "replay",
    "preflight",
]
