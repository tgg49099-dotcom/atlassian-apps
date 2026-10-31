"""The control plan — auditable, named, ordered rules that rank the risk.

Three properties are non-negotiable here:

1. **No model in the loop.**  The verdict that makes an order executable must
   be reproducible, diffable and reviewable.  This module imports nothing but
   the standard library and the vocabulary of :mod:`signedorders.models`; a
   test asserts that.
2. **Named rules.**  Every rule has an id, a one-sentence explanation and a
   risk level.  ``Policy.describe()`` returns the whole table, which is what
   the README and the UI show.
3. **A trace.**  Every decision carries the rule that fired, the input it was
   given and the output it produced (:class:`signedorders.models.Trace`).

The order of the rules is part of the contract: the first matching rule wins,
and the forbidden rules come first so that nothing can be talked out of them.

Two refusals, and they are not the same thing:

* ``Risk.FORBIDDEN`` -> refused by policy; **no human approval can unlock it**;
* ``Risk.HIGH``      -> queued for a human, who may approve it.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Callable, Iterable

from .models import Action, Order, Risk, Trace

__all__ = [
    "Policy",
    "Rule",
    "DEFAULT_EGRESS_ALLOWLIST",
    "classify",
    "default_policy",
]


# --------------------------------------------------------------------------- #
# Targets
# --------------------------------------------------------------------------- #
#
# An order always names its target with an explicit scheme, so the policy
# never has to guess:
#
#   sandbox://sb-42:/app/main.py   a file of the fresh sandbox
#   service://checkout-api          the target service (mutating)
#   net://api.example.com/v1       the outside world
#   host://etc/shadow              the machine that runs the control plane

SANDBOX_SCHEME = "sandbox://"
SERVICE_SCHEME = "service://"
NETWORK_SCHEME = "net://"
HOST_SCHEMES = ("host://", "file://")


def _path_of(target: str) -> str:
    """The path part of a ``scheme://id:/path`` target."""
    if "://" not in target:
        return target
    rest = target.split("://", 1)[1]
    if ":" in rest:
        return rest.split(":", 1)[1]
    return rest


def _host_of(target: str) -> str:
    """The host part of a ``net://host/path`` target."""
    rest = target.split("://", 1)[1] if "://" in target else target
    host = rest.split("/", 1)[0]
    return host.split(":", 1)[0]


def action_value(action: Any) -> str:
    """The action as text, even when it is not a known action.

    An unknown action must not crash the policy: it must fall through to the
    conservative default (a human decides).
    """
    return action.value if isinstance(action, Action) else str(action)


def _command_of(order: Order) -> str:
    """The command line of an order, whatever the action."""
    args = order.args or {}
    parts: list[str] = []
    argv = args.get("argv")
    if isinstance(argv, (list, tuple)):
        parts.extend(str(item) for item in argv)
    for key in ("command", "cmd"):
        value = args.get(key)
        if isinstance(value, str):
            parts.append(value)
    return " ".join(parts)


# --------------------------------------------------------------------------- #
# The patterns
# --------------------------------------------------------------------------- #

#: Anything that would let the order reach the machine that runs the control
#: plane, or escape the sandbox root.
ESCAPE_PATTERNS: tuple[tuple[str, str], ...] = (
    (r"^\s*/", "the target is an absolute path on the host"),
    (r"(^|/)\.\.(/|$)", "the target walks out of the sandbox root with '..'"),
    (r"^host://", "the target scheme 'host://' addresses the control plane host"),
    (r"^file://", "the target scheme 'file://' addresses the control plane host"),
)

#: Privilege escalation inside the sandbox.
PRIVILEGE_PATTERNS: tuple[tuple[str, str], ...] = (
    (r"--privileged", "the command asks for --privileged"),
    (r"--cap-add", "the command asks for an extra capability"),
    (r"--pid=host", "the command asks to share the host PID namespace"),
    (r"--net=host", "the command asks to share the host network namespace"),
    (r"--userns=host", "the command asks to share the host user namespace"),
    (r"(^|\s)sudo(\s|$)", "the command asks for sudo"),
    (r"(^|\s)su(\s|$)", "the command asks for su"),
    (r"(^|\s)chroot(\s|$)", "the command asks for chroot"),
    (r"(^|\s)mount(\s|$)", "the command asks to mount a filesystem"),
    (r"(^|\s)nsenter(\s|$)", "the command asks to enter another namespace"),
    (r"(^|\s)unshare(\s|$)", "the command asks to create namespaces"),
    (r"(^|\s)mkfs(\S*)(\s|$)", "the command formats a filesystem"),
    (r"(^|\s)setcap(\s|$)", "the command changes the capabilities of a binary"),
)

#: Reading a credential.
SECRET_PATTERNS: tuple[tuple[str, str], ...] = (
    (r"/proc/self/environ", "the command reads the environment of the process"),
    (r"(^|\s)printenv(\s|$)", "the command dumps the environment"),
    (r"(^|\s)env(\s|$)", "the command dumps the environment"),
    (r"(^|/)\.env(\.[A-Za-z]+)?$", "the target is an environment file"),
    (r"(^|/)\.netrc$", "the target is an .netrc credential file"),
    (r"(^|/)\.npmrc$", "the target is an .npmrc credential file"),
    (r"(^|/)\.ssh/", "the target is an SSH key directory"),
    (r"(^|/)\.aws/", "the target is an AWS credential directory"),
    (r"(^|/)\.gnupg/", "the target is a GnuPG key directory"),
    (r"(^|/)id_rsa$", "the target is an SSH private key"),
    (r"(^|/)id_ed25519$", "the target is an SSH private key"),
    (r"(^|/)shadow$", "the target is the password shadow file"),
    (r"(^|/)passwd$", "the target is the password database"),
    (r"\.(pem|key|p12|pfx|jks|keystore)$", "the target is a private key file"),
    (r"(^|/)credentials$", "the target is a credentials file"),
)

#: The cloud metadata service: a well-known way to steal a credential.
METADATA_PATTERNS: tuple[tuple[str, str], ...] = (
    (r"169\.254\.169\.254", "the target is the cloud metadata service"),
    (r"metadata\.google\.internal", "the target is the cloud metadata service"),
    (r"metadata\.goog$", "the target is the cloud metadata service"),
    (r"fd00:ec2::254", "the target is the cloud metadata service"),
    (r"100\.100\.100\.200", "the target is the cloud metadata service"),
)

#: Destructive commands: legitimate, but they always need a human.
DESTRUCTIVE_PATTERNS: tuple[tuple[str, str], ...] = (
    (r"(^|\s)rm(\s|$)", "the command removes files"),
    (r"(^|\s)rmdir(\s|$)", "the command removes directories"),
    (r"(^|\s)unlink(\s|$)", "the command unlinks a file"),
    (r"(^|\s)truncate(\s|$)", "the command truncates a file"),
    (r"(^|\s)shred(\s|$)", "the command overwrites a file"),
    (r"(^|\s)dd(\s|$)", "the command writes raw blocks"),
    (r"\bDROP\s+(TABLE|DATABASE)\b", "the command drops a table or a database"),
    (r"\bDELETE\s+FROM\b", "the command deletes rows"),
    (r"\bTRUNCATE\s+TABLE\b", "the command truncates a table"),
    (r"(^|\s)systemctl\s+(stop|disable)(\s|$)", "the command stops a service"),
    (r"(^|\s)docker\s+(rm|rmi|system\s+prune)(\s|$)", "the command removes containers or images"),
    (r"(^|\s)kill\s+-9(\s|$)", "the command kills a process by force"),
    (r"(^|\s)git\s+reset\s+--hard(\s|$)", "the command discards the work tree"),
    (r"(^|\s)git\s+clean(\s|$)", "the command removes untracked files"),
    (r"(^|\s)shutdown(\s|$)", "the command shuts the machine down"),
    (r"(^|\s)reboot(\s|$)", "the command reboots the machine"),
)

#: Commands that reach the outside world.
NETWORK_COMMAND_PATTERNS: tuple[str, ...] = (
    r"(^|\s)curl(\s|$)",
    r"(^|\s)wget(\s|$)",
    r"(^|\s)nc(\s|$)",
    r"(^|\s)ncat(\s|$)",
    r"(^|\s)ssh(\s|$)",
    r"(^|\s)scp(\s|$)",
    r"(^|\s)sftp(\s|$)",
    r"(^|\s)ftp(\s|$)",
    r"(^|\s)rsync(\s|$)",
)

#: Commands that change the target service.
MUTATING_PATTERNS: tuple[str, ...] = (
    r"(^|\s)systemctl\s+restart(\s|$)",
    r"(^|\s)service\s+\S+\s+restart(\s|$)",
    r"(^|\s)docker\s+restart(\s|$)",
    r"(^|\s)nginx\s+-s\s+reload(\s|$)",
)

#: Hosts the sandbox may reach when an egress allowlist is set.
DEFAULT_EGRESS_ALLOWLIST: tuple[str, ...] = ()


# --------------------------------------------------------------------------- #
# The rules
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class Rule:
    """One named, auditable rule.

    ``explanation`` is a single sentence that a judge can read without reading
    the code, and it is stored in the trace of every decision the rule
    produced.
    """

    id: str
    risk: Risk
    explanation: str
    match: Callable[[Order], bool]
    inputs: tuple[str, ...] = ("action", "target", "args")

    def applies_to(self, order: Order) -> bool:
        return bool(self.match(order))

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "risk": self.risk.value,
            "explanation": self.explanation,
            "inputs": list(self.inputs),
        }


def _any_pattern(patterns: Iterable[tuple[str, str]], value: str) -> str | None:
    """Return the explanation of the first pattern that matches ``value``."""
    for pattern, explanation in patterns:
        if re.search(pattern, value):
            return explanation
    return None


def _matches_patterns(patterns: Iterable[tuple[str, str]], value: str) -> bool:
    return _any_pattern(patterns, value) is not None


def _escape_rule(order: Order) -> bool:
    target = order.target or ""
    if target.startswith(HOST_SCHEMES):
        return True
    return _matches_patterns(ESCAPE_PATTERNS, target)


def _privilege_rule(order: Order) -> bool:
    return _matches_patterns(PRIVILEGE_PATTERNS, _command_of(order))


def _secret_rule(order: Order) -> bool:
    command = _command_of(order)
    if _matches_patterns(SECRET_PATTERNS, command):
        return True
    return _matches_patterns(SECRET_PATTERNS, _path_of(order.target or ""))


def _metadata_rule(order: Order) -> bool:
    command = _command_of(order)
    return _matches_patterns(METADATA_PATTERNS, command) or _matches_patterns(
        METADATA_PATTERNS, order.target or ""
    )


def _destructive_rule(order: Order) -> bool:
    if order.action is Action.DELETE:
        return True
    return _matches_patterns(DESTRUCTIVE_PATTERNS, _command_of(order))


def _network_rule(order: Order) -> bool:
    if order.action is Action.NETWORK:
        return True
    command = _command_of(order)
    return any(re.search(pattern, command) for pattern in NETWORK_COMMAND_PATTERNS)


def _mutating_rule(order: Order) -> bool:
    if order.action in (Action.APPLY, Action.RESTART):
        return True
    command = _command_of(order)
    return any(re.search(pattern, command) for pattern in MUTATING_PATTERNS)


#: The rule table.  **The order is part of the contract**: the first rule that
#: matches wins, and the forbidden rules are first on purpose.
RULES: tuple[Rule, ...] = (
    Rule(
        id="forbidden.escape",
        risk=Risk.FORBIDDEN,
        explanation=(
            "The order tries to reach the host or to walk out of the sandbox root, "
            "so it is refused and no human approval can unlock it."
        ),
        match=_escape_rule,
        inputs=("target",),
    ),
    Rule(
        id="forbidden.privilege_escalation",
        risk=Risk.FORBIDDEN,
        explanation=(
            "The order asks for a privileged capability (--privileged, sudo, chroot, "
            "namespace sharing), so it is refused and no human approval can unlock it."
        ),
        match=_privilege_rule,
        inputs=("args",),
    ),
    Rule(
        id="forbidden.secret_read",
        risk=Risk.FORBIDDEN,
        explanation=(
            "The order tries to read a credential (private key, environment file, "
            "process environment), so it is refused and no human approval can unlock it."
        ),
        match=_secret_rule,
        inputs=("target", "args"),
    ),
    Rule(
        id="forbidden.cloud_metadata",
        risk=Risk.FORBIDDEN,
        explanation=(
            "The order targets the cloud metadata service, which is the usual way to "
            "steal a credential, so it is refused and no human approval can unlock it."
        ),
        match=_metadata_rule,
        inputs=("target", "args"),
    ),
    Rule(
        id="risk.destructive",
        risk=Risk.HIGH,
        explanation=(
            "The order deletes or overwrites something, so it waits for a signed human "
            "approval."
        ),
        match=_destructive_rule,
        inputs=("action", "args"),
    ),
    Rule(
        id="risk.network",
        risk=Risk.HIGH,
        explanation=(
            "The order reaches the outside world on a host that is not on the egress "
            "allowlist, so it waits for a signed human approval."
        ),
        match=_network_rule,
        inputs=("action", "target", "args"),
    ),
    Rule(
        id="risk.mutating_target",
        risk=Risk.MEDIUM,
        explanation=(
            "The order changes the target service, so it runs automatically but only "
            "after a snapshot, with the rollback armed."
        ),
        match=_mutating_rule,
        inputs=("action", "args"),
    ),
    Rule(
        id="allow.sandbox_read",
        risk=Risk.LOW,
        explanation="The order only reads a file of the sandbox, which changes nothing.",
        match=lambda order: order.action is Action.READ,
        inputs=("action",),
    ),
    Rule(
        id="allow.sandbox_write",
        risk=Risk.LOW,
        explanation=(
            "The order writes a file of the sandbox, which is discarded when the "
            "sandbox is destroyed."
        ),
        match=lambda order: order.action is Action.WRITE,
        inputs=("action",),
    ),
    Rule(
        id="allow.sandbox_test",
        risk=Risk.LOW,
        explanation="The order runs the test suite of the sandbox, which changes nothing.",
        match=lambda order: order.action is Action.TEST,
        inputs=("action",),
    ),
    Rule(
        id="allow.sandbox_exec",
        risk=Risk.LOW,
        explanation=(
            "The order runs a command inside the sandbox, which is contained by the "
            "resource limits and the wall-clock timeout."
        ),
        match=lambda order: order.action is Action.EXEC,
        inputs=("action",),
    ),
    Rule(
        id="allow.escalate",
        risk=Risk.LOW,
        explanation=(
            "The order asks a human a question, which cannot change anything by "
            "itself."
        ),
        match=lambda order: order.action is Action.ESCALATE,
        inputs=("action",),
    ),
    Rule(
        id="default.unknown",
        risk=Risk.HIGH,
        explanation=(
            "The order does not match any known case, so the conservative default "
            "applies: a human decides."
        ),
        match=lambda order: True,
        inputs=("action", "target", "args"),
    ),
)


# --------------------------------------------------------------------------- #
# The policy
# --------------------------------------------------------------------------- #


@dataclass
class Policy:
    """The rule table plus the egress allowlist.

    The object holds **no state**: evaluating an order never mutates it, so
    the same order always gets the same verdict.
    """

    rules: tuple[Rule, ...] = RULES
    egress_allowlist: tuple[str, ...] = DEFAULT_EGRESS_ALLOWLIST

    # -- evaluation -------------------------------------------------------- #

    def evaluate(self, order: Order) -> Trace:
        """Return the trace of the decision: rule, input, output."""
        evaluated: list[str] = []
        for rule in self.rules:
            evaluated.append(rule.id)
            if not rule.applies_to(order):
                continue
            if rule.id == "risk.network" and self._network_is_allowed(order):
                rule = Rule(
                    id="allow.network_egress",
                    risk=Risk.LOW,
                    explanation=(
                        "The order reaches a host on the egress allowlist, so it runs "
                        "automatically."
                    ),
                    match=rule.match,
                    inputs=rule.inputs,
                )
            inputs = self._inputs_for(rule, order)
            outputs = {"risk": rule.risk.value, "rule_id": rule.id}
            return Trace(
                rule_id=rule.id,
                explanation=rule.explanation,
                inputs=inputs,
                outputs=outputs,
                evaluated=tuple(evaluated),
                consulted=rule.inputs,
            )
        # Unreachable: the last rule matches everything.
        raise AssertionError("the rule table must end with a catch-all rule")

    def classify(self, order: Order) -> tuple[Risk, str, str]:
        """Return ``(risk, rule_id, explanation)`` — the short form."""
        trace = self.evaluate(order)
        return Risk(trace.outputs["risk"]), trace.rule_id, trace.explanation

    # -- helpers ----------------------------------------------------------- #

    def _network_is_allowed(self, order: Order) -> bool:
        """True when the network target is on the egress allowlist.

        The host is looked for in the ``net://`` target *and* in the URLs of
        the command, because an agent usually writes the URL in the command
        and leaves the target generic.
        """
        if not self.egress_allowlist:
            return False
        candidates: set[str] = set()
        host = _host_of(order.target or "")
        if host:
            candidates.add(host)
        for url_host in re.findall(r"https?://([^/\s:'\"]+)", _command_of(order)):
            candidates.add(url_host)
        return bool(candidates & set(self.egress_allowlist))

    def _inputs_for(self, rule: Rule, order: Order) -> dict[str, Any]:
        """The values the rule was given — the ``input`` of the trace.

        The whole order is always shown: an auditor must be able to see what
        the rule was given, not only the fields it happened to read.
        """
        return {
            "action": action_value(order.action),
            "target": order.target,
            "args": dict(order.args or {}),
        }

    def describe(self) -> list[dict[str, Any]]:
        """The whole rule table, in order — for the README, the UI and the tests."""
        table = [rule.to_dict() for rule in self.rules]
        table.append(
            {
                "id": "allow.network_egress",
                "risk": Risk.LOW.value,
                "explanation": (
                    "The order reaches a host on the egress allowlist, so it runs "
                    "automatically."
                ),
                "inputs": ["action", "target", "args"],
            }
        )
        return table


def default_policy() -> Policy:
    """The policy used by the control plane when nothing else is configured."""
    return Policy()


def classify(order: Order) -> tuple[Risk, str, str]:
    """Classify an order with the default policy."""
    return default_policy().classify(order)
