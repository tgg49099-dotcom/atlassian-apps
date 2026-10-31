"""Risk classification — the auditable rules of the control plane.

This module is deliberately **not** allowed to call a model. The decision that
makes an order executable must be reproducible, diffable and reviewable: the
same order must always get the same verdict, and a judge must be able to read
the rule that produced it.

Two kinds of refusal exist, and they are not the same thing:

* ``Risk.HIGH``      -> queued for a human; a human may approve it.
* ``Risk.FORBIDDEN`` -> refused by policy; **no human override exists**.

TODO(build-window): fill the rule table from ``policy/rules.yaml`` so the rules
can be edited without touching code, and add the unit tests of each rule.
"""

from __future__ import annotations

from dataclasses import dataclass

from .models import Action, Order, Risk

# --------------------------------------------------------------------------- #
# Rules
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class Rule:
    """One auditable rule: a match on the action/target, and its consequence."""

    id: str
    risk: Risk
    description: str
    #: ``fn`` receives the order and returns True when the rule matches.
    match: object = None

    def applies_to(self, order: Order) -> bool:
        if self.match is None:
            return False
        return bool(self.match(order))  # type: ignore[operator]


#: Orders that are dangerous by nature: they would leak a secret or break out
#: of the sandbox. They are refused even if a human says yes.
FORBIDDEN_PATTERNS: tuple[str, ...] = (
    r"^/etc/(?!signedorders)",       # host configuration
    r"^/proc/",
    r"^/dev/",
    r"(^|/)\.ssh/",
    r"(^|/)\.aws/",
    r"(^|/)\.env$",
    r"\.(pem|key|p12|pfx)$",
)

#: Commands that are never auto-executed.
FORBIDDEN_COMMANDS: tuple[str, ...] = (
    r"\brm\s+-rf?\s+/(?:\s|$)",
    r"\bdd\b.*\bof=/dev/",
    r"\bmkfs\b",
    r":\(\)\s*\{",                     # fork bomb
    r"\bshutdown\b|\breboot\b|\bhalt\b",
    r"\bcurl\b.*\|\s*(ba)?sh\b",       # pipe to shell
    r"\bwget\b.*\|\s*(ba)?sh\b",
)

#: Hosts the sandbox is allowed to reach when an egress allowlist is set.
DEFAULT_EGRESS_ALLOWLIST: tuple[str, ...] = ()

# TODO(build-window): the real rule table.
RULES: tuple[Rule, ...] = ()


def classify(order: Order) -> tuple[Risk, str, str]:
    """Return ``(risk, rule_id, reason)`` for an order.

    The order of the checks matters and is part of the contract:

    1. forbidden patterns   -> ``Risk.FORBIDDEN`` (no override)
    2. forbidden commands   -> ``Risk.FORBIDDEN`` (no override)
    3. explicit rule table  -> the risk declared by the matching rule
    4. default per action   -> the conservative default
    """
    target = order.target or ""
    args = order.args or {}

    for pattern in FORBIDDEN_PATTERNS:
        if _matches(pattern, target):
            return (Risk.FORBIDDEN, "forbidden.path",
                    f"the target matches the forbidden pattern {pattern!r}")

    command = str(args.get("command", ""))
    for pattern in FORBIDDEN_COMMANDS:
        if _matches(pattern, command):
            return (Risk.FORBIDDEN, "forbidden.command",
                    f"the command matches the forbidden pattern {pattern!r}")

    for rule in RULES:
        if rule.applies_to(order):
            return (rule.risk, rule.id, rule.description)

    return _default_for(order)


def _default_for(order: Order) -> tuple[Risk, str, str]:
    """Conservative defaults when no explicit rule matched."""
    match order.action:
        case Action.READ:
            return (Risk.LOW, "default.read",
                    "reading a file inside the sandbox changes nothing")
        case Action.WRITE:
            return (Risk.LOW, "default.write",
                    "writing inside the sandbox is reversible by destroying it")
        case Action.EXEC:
            return (Risk.LOW, "default.exec",
                    "running a command inside the sandbox is contained by the "
                    "resource limits")
        case Action.APPLY | Action.RESTART:
            return (Risk.MEDIUM, "default.mutating",
                    "this changes the target service: a snapshot is taken first "
                    "and a rollback is armed")
        case Action.NETWORK:
            return (Risk.HIGH, "default.network",
                    "an outbound call can leak data: a human decides")
        case Action.ESCALATE:
            return (Risk.LOW, "default.escalate",
                    "asking a human is always allowed")
        case _:
            return (Risk.HIGH, "default.unknown",
                    "unknown action: a human decides")


def _matches(pattern: str, value: str) -> bool:
    import re

    return re.search(pattern, value) is not None


__all__ = [
    "DEFAULT_EGRESS_ALLOWLIST",
    "FORBIDDEN_COMMANDS",
    "FORBIDDEN_PATTERNS",
    "RULES",
    "Rule",
    "classify",
]
