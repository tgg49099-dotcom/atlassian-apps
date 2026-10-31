"""Command line entry point.

    signedorders serve       start the control plane (API + UI) on the Vultr VM
    signedorders init-key    generate the Ed25519 signing key (host only)
    signedorders verify      verify a journal offline — no secret needed
    signedorders replay      re-run a recorded task in a fresh sandbox
    signedorders preflight   scan the tree for secrets before a commit

TODO(build-window): wire the commands to the services.
"""

from __future__ import annotations

import argparse
import sys

COMMANDS = ("serve", "init-key", "verify", "replay", "preflight")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="signedorders",
        description="Containment-first control plane for autonomous agents.",
    )
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("serve", help="start the control plane")
    sub.add_parser("init-key", help="generate the signing key on this host")
    verify = sub.add_parser("verify", help="verify a journal (no secret needed)")
    verify.add_argument("journal", help="path to the JSONL journal")
    replay = sub.add_parser("replay", help="replay a recorded task")
    replay.add_argument("journal", help="path to the JSONL journal")
    replay.add_argument("--task", required=True, help="task id to replay")
    preflight = sub.add_parser("preflight", help="scan the tree for secrets")
    preflight.add_argument("root", nargs="?", default=".")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv or sys.argv[1:])
    match args.command:
        case "serve":
            raise NotImplementedError("TODO(build-window)")
        case "init-key":
            raise NotImplementedError("TODO(build-window)")
        case "verify":
            raise NotImplementedError("TODO(build-window)")
        case "replay":
            raise NotImplementedError("TODO(build-window)")
        case "preflight":
            from .preflight import main as preflight_main

            root = args.root
            return preflight_main([root])
        case _:
            print(f"unknown command {args.command!r}", file=sys.stderr)
            return 2


if __name__ == "__main__":
    raise SystemExit(main())
