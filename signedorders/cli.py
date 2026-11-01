"""The command line — the tools a judge can run without a server.

    signedorders verify <journal.jsonl>   check the hash chain offline
    signedorders replay <journal.jsonl>   read the story back, in order
    signedorders policy                   print the rule table
    signedorders init-key <path>          generate the signing key (host only)
    signedorders sign <payload.json>      sign a payload with the host key
    signedorders preflight [root]         scan the tree for secrets
    signedorders serve                    run the HTTP door of the control plane

Every command works with a **bare Python 3.10+**: the core has no
dependency, so a judge can clone the repository and check the journal
without installing anything.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from .journal import Journal
from .policy import default_policy
from .signer import Signer

__all__ = ["build_parser", "main"]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="signedorders",
        description="Containment-first control plane for autonomous agents.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    verify = sub.add_parser("verify", help="verify a journal (no secret needed)")
    verify.add_argument("journal", help="path to the JSONL journal")

    replay = sub.add_parser("replay", help="replay a recorded journal")
    replay.add_argument("journal", help="path to the JSONL journal")

    sub.add_parser("policy", help="print the rule table of the control plan")

    init_key = sub.add_parser("init-key", help="generate the signing key on this host")
    init_key.add_argument("path", nargs="?", default="/etc/signedorders/signing.key")

    sign = sub.add_parser("sign", help="sign a JSON payload with the host key")
    sign.add_argument("payload", help="path to a JSON file")
    sign.add_argument("--key", default="/etc/signedorders/signing.key")

    preflight = sub.add_parser("preflight", help="scan the tree for secrets")
    preflight.add_argument("root", nargs="?", default=".")

    serve = sub.add_parser(
        "serve",
        help="run the HTTP door of the control plane (the only public surface)",
    )
    serve.add_argument("--host", default=None, help="interface to bind")
    serve.add_argument("--port", type=int, default=None, help="port to listen on")
    serve.add_argument("--journal", default=None, help="path of the JSONL journal")
    serve.add_argument(
        "--key",
        default=None,
        help="path of the Ed25519 signing key (default: $SIGNEDORDERS_SIGNING_KEY_PATH)",
    )

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv if argv is not None else sys.argv[1:])
    match args.command:
        case "verify":
            return _verify(args.journal)
        case "replay":
            return _replay(args.journal)
        case "policy":
            return _policy()
        case "init-key":
            return _init_key(args.path)
        case "sign":
            return _sign(args.payload, args.key)
        case "preflight":
            from .preflight import main as preflight_main

            return preflight_main([args.root])
        case "serve":
            return _serve(args)
        case _:
            print(f"unknown command {args.command!r}", file=sys.stderr)
            return 2


def _verify(path: str) -> int:
    journal = Journal(path)
    report = journal.verify()
    if report.ok:
        print(f"OK — the chain is intact ({report.entries_checked} entries checked)")
        return 0
    print(f"FAILED — the chain is broken at entry {report.broken_index}")
    print(f"reason: {report.reason}")
    return 1


def _replay(path: str) -> int:
    journal = Journal(path)
    result = journal.replay()
    print(f"{result.count} entries replayed")
    for kind, number in sorted(result.kinds.items()):
        print(f"  {kind}: {number}")
    for task in result.tasks:
        print(f"  task {task}")
    return 0


def _policy() -> int:
    table = default_policy().describe()
    print(json.dumps(table, indent=2, sort_keys=True))
    return 0


def _init_key(path: str) -> int:
    signer = Signer.generate(path)
    print(f"key written to {path} (mode 0600)")
    print(f"public key: {signer.public_key_b64}")
    print(f"key id:     {signer.key_id}")
    return 0


def _sign(payload_path: str, key_path: str) -> int:
    payload = json.loads(Path(payload_path).read_text(encoding="utf-8"))
    signer = Signer(key_path=key_path)
    envelope = signer.sign(payload)
    print(json.dumps(envelope.to_dict(), indent=2, sort_keys=True))
    return 0


def _serve(args: argparse.Namespace) -> int:
    """Run the HTTP door of the control plane.

    The bearer keys are read from the **environment only** (``SIGNEDORDERS_AGENT_KEY``
    and ``SIGNEDORDERS_OPERATOR_KEY``): a key given on the command line would be
    visible in ``ps`` to every user of the machine.
    """
    from .config import settings_from_env
    from .server import serve as serve_http

    settings = settings_from_env()

    host = args.host or settings.host
    port = settings.port if args.port is None else args.port
    journal_path = Path(args.journal or settings.journal_path)
    journal_path.parent.mkdir(parents=True, exist_ok=True)

    key_path = args.key or settings.signing_key_path
    signing_key = None
    if key_path and Path(key_path).exists():
        signing_key = key_path
    elif key_path:
        print(
            f"WARNING: no signing key at {key_path} — an ephemeral key is generated for "
            "this run; the journal it writes cannot be verified against the committed "
            "public key. Run 'signedorders init-key' first."
        )

    serve_http(
        host=host,
        port=port,
        journal_path=journal_path,
        signing_key_path=signing_key,
        agent_key=settings.agent_key,
        operator_key=settings.operator_key,
        quota_limits=settings.quota_limits(),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
