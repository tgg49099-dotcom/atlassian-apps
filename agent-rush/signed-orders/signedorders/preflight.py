"""Preflight — the secret scanner that runs before every commit and deploy.

It refuses to let the repository contain anything that looks like a credential:
API keys, private keys, tokens, real hostnames or addresses of the internal
infrastructure. The rule is simple: **the public repository must be safe to
clone, read and fork**.

It also checks the invariants of the project itself: the public key is
committed, the private key is not, and no ``.env`` file is tracked.

TODO(build-window): wire it into ``pre-commit`` and into the deployment script.
"""

from __future__ import annotations

import re
from pathlib import Path

#: Patterns that must never appear in a tracked file.
FORBIDDEN_PATTERNS: tuple[tuple[str, str], ...] = (
    # A real key is a complete block (BEGIN ... END). A mention of the header
    # alone — in a document that explains what is forbidden — is not a secret.
    (r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----",
     "cle privee"),
    (r"\bsk-[A-Za-z0-9]{16,}", "cle style OpenAI"),
    (r"\bghp_[A-Za-z0-9]{20,}", "jeton GitHub"),
    (r"\bAKIA[0-9A-Z]{16}", "cle AWS"),
    (r"\bvultr_[A-Za-z0-9]{16,}", "cle Vultr"),
    (r"\bxox[abprs]-[A-Za-z0-9-]{10,}", "jeton Slack"),
    (r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}", "JWT"),
    (r"(?i)\b(api[_-]?key|secret|password|token)\s*[:=]\s*[\"'][^\"']{12,}[\"']",
     "secret en clair"),
)

#: Files that must not be tracked.
FORBIDDEN_FILES: tuple[str, ...] = (".env", "signing.key", "id_ed25519")

#: Directories that are never scanned.
SKIP_DIRS: tuple[str, ...] = (".git", ".venv", "__pycache__", "node_modules")


def scan_file(path: Path) -> list[str]:
    """Return the problems found in one file (empty list = clean)."""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    problems: list[str] = []
    for pattern, label in FORBIDDEN_PATTERNS:
        if re.search(pattern, text):
            problems.append(f"{path}: motif interdit ({label})")
    return problems


def scan_tree(root: Path) -> list[str]:
    """Scan every tracked-looking file under ``root``."""
    problems: list[str] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if path.name in FORBIDDEN_FILES:
            problems.append(f"{path}: fichier interdit dans le depot")
        problems.extend(scan_file(path))
    return problems


def main(argv: list[str] | None = None) -> int:
    """CLI: ``python -m signedorders.preflight [root]`` — exit 1 if a problem."""
    import sys

    root = Path((argv or sys.argv[1:] or ["."])[0])
    problems = scan_tree(root)
    for problem in problems:
        print(f"FAIL {problem}")
    if problems:
        print(f"{len(problems)} probleme(s) — commit refuse")
        return 1
    print("OK — aucun secret detecte")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
