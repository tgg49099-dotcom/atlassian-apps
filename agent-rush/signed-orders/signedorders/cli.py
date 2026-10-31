"""The command line — the tools a judge can run without a server.

    signedorders verify <journal.jsonl>   check the hash chain offline
    signedorders replay <journal.jsonl>   read the story back, in order
    signedorders policy                   print the rule table
    signedorders init-key <path>          generate the signing key (host only)
    signedorders sign <payload.json>      sign a payload with the host key
    signedorders preflight [root]         scan the tree for secrets

Every command works with a **bare Python 3.10+**: the core has no
dependency, so a judge can clone the repository and check the journal
without installing anything.
"""

from __future__ import annotations

import argparse
import json
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


if __name__ == "__main__":
    raise SystemExit(main())
