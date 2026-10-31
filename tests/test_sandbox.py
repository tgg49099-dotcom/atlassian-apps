"""The sandbox — the exact isolation, and the exact command line.

This container has **no Docker**, so the container is not started here.  What
is tested is the thing that matters and that *can* be tested: the manifest,
the flags, and the prohibitions.  The command line is compared with the list
the challenge requires, flag by flag.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from signedorders.sandbox import (
    DockerBackend,
    MicrosandboxBackend,
    RecordingBackend,
    SandboxManifest,
    default_manifest,
)

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "run-in-sandbox.sh"
MANIFEST_FILE = ROOT / "scripts" / "sandbox-manifest.json"


@pytest.fixture()
def backend() -> DockerBackend:
    return DockerBackend()


@pytest.fixture()
def command_line(backend: DockerBackend) -> list[str]:
    return backend.build_command(
        default_manifest(),
        ["pytest", "-q"],
        Path("/tmp/workspace-task-1"),
        "sb-test-1",
    )


# --------------------------------------------------------------------------- #
# The flags the challenge requires
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    "flag",
    [
        "--network=none",
        "--memory=256m",
        "--cpus=1",
        "--read-only",
        "--cap-drop=ALL",
        "--pids-limit=128",
        "--security-opt=no-new-privileges",
        "--user=65534:65534",
        "--tmpfs=/tmp:rw,noexec,nosuid,size=64m",
        "--rm",
    ],
)
def test_the_required_flags_are_present(command_line, flag):
    assert flag in command_line


@pytest.mark.parametrize(
    "forbidden",
    [
        "--privileged",
        "--cap-add",
        "--network=host",
        "--net=host",
        "--pid=host",
        "--ipc=host",
        "--userns=host",
        "--device",
        "--volume=/:/",
        "-v /:/",
        "--env",
        "--env-file",
    ],
)
def test_the_dangerous_flags_are_absent(command_line, forbidden):
    assert forbidden not in command_line


def test_the_container_gets_no_environment_variable(command_line):
    """The inference key must never reach the sandbox."""
    assert not [argument for argument in command_line if argument.startswith("--env")]


def test_the_container_is_started_as_nobody(command_line):
    assert "--user=65534:65534" in command_line


def test_there_is_exactly_one_volume(command_line):
    assert command_line.count("--volume") == 1
    volume = command_line[command_line.index("--volume") + 1]
    assert volume == "/tmp/workspace-task-1:/work:rw"


def test_the_volume_is_the_workspace_of_the_task(command_line):
    volume = command_line[command_line.index("--volume") + 1]
    assert volume.startswith("/tmp/workspace-task-1:")
    assert volume.endswith(":/work:rw")


def test_the_command_line_ends_with_the_image_and_the_script(command_line):
    assert command_line[-4:] == ["debian:bookworm-slim", "/work/run-in-sandbox.sh",
                                 "pytest", "-q"]


def test_the_network_is_cut(command_line):
    assert command_line.count("--network=none") == 1
    assert not [argument for argument in command_line if "network" in argument
                and argument != "--network=none"]


# --------------------------------------------------------------------------- #
# The manifest
# --------------------------------------------------------------------------- #


def test_the_manifest_file_matches_the_code():
    """The published manifest must be the one the code builds."""
    published = json.loads(MANIFEST_FILE.read_text(encoding="utf-8"))
    assert published == default_manifest().to_dict()


def test_the_manifest_round_trips():
    manifest = default_manifest()
    assert SandboxManifest.from_dict(manifest.to_dict()) == manifest


def test_the_manifest_declares_no_environment_variable():
    assert default_manifest().env_allowlist == ()


def test_the_manifest_has_a_wall_clock_timeout():
    assert 0 < default_manifest().timeout_seconds <= 300


def test_the_manifest_destroys_the_sandbox():
    assert default_manifest().destroy_on_exit is True


def test_the_second_backend_declares_the_same_isolation():
    """The microVM backend must be as closed as the container one."""
    command = MicrosandboxBackend().build_command(
        default_manifest(), ["pytest", "-q"], Path("/tmp/ws"), "sb-test-1"
    )
    assert "--network=none" in command
    assert "--read-only" in command
    assert "--cap-drop=ALL" in command
    assert "--cpus=1" in command
    assert "--memory=256M" in command
    assert not [argument for argument in command if "host" in argument]


def test_the_two_backends_share_the_same_manifest():
    assert DockerBackend().build_command(
        default_manifest(), ["x"], Path("/tmp/w"), "s"
    )[-1] == MicrosandboxBackend().build_command(
        default_manifest(), ["x"], Path("/tmp/w"), "s"
    )[-1]


def test_the_recording_backend_records_instead_of_running(tmp_path):
    backend = RecordingBackend()
    backend.run(default_manifest(), ["pytest"], tmp_path, "sb-1")
    assert backend.calls[0]["command"] == ["pytest"]
    assert backend.calls[0]["manifest"]["network"] == "none"


# --------------------------------------------------------------------------- #
# The script that runs inside the sandbox
# --------------------------------------------------------------------------- #


def test_the_script_exists():
    assert SCRIPT.exists()


def test_the_script_is_executable():
    assert SCRIPT.stat().st_mode & 0o111


def test_the_script_never_reaches_the_network():
    source = SCRIPT.read_text(encoding="utf-8")
    code = "\n".join(
        line for line in source.splitlines() if not line.strip().startswith("#")
    )
    for needle in ("curl", "wget", "nc ", "netcat", "ssh ", "ftp"):
        assert needle not in code, f"the sandbox script must not use {needle!r}"


def test_the_script_has_a_wall_clock_timeout():
    source = SCRIPT.read_text(encoding="utf-8")
    assert "timeout" in source
    assert "SIGNEDORDERS_TIMEOUT_SECONDS" in source


def test_the_script_writes_only_inside_the_workspace():
    source = SCRIPT.read_text(encoding="utf-8")
    redirections = re.findall(r'>\s*"?(\$[A-Za-z_]+/[^"\s]+)', source)
    assert redirections, "the script must capture the output of the command"
    assert all(target.startswith("$LOGDIR/") for target in redirections)
    assert "$WORKDIR/.signedorders" in source


def test_the_script_fails_softly_on_an_empty_command():
    source = SCRIPT.read_text(encoding="utf-8")
    assert '"$#" -eq 0' in source


def test_the_script_prints_one_json_line():
    source = SCRIPT.read_text(encoding="utf-8")
    assert "sandbox_result" in source
    assert source.count("printf") >= 1


def test_the_script_is_posix_sh():
    source = SCRIPT.read_text(encoding="utf-8")
    assert source.startswith("#!/bin/sh")
    assert "set -eu" in source
