"""The executor — a fresh, isolated sandbox for every order.

Two things live here:

* the **manifest**: the exact description of the isolation (image, network,
  memory, cpus, read-only root, capabilities, user, volumes, timeout);
* the **command line**: the exact ``docker run`` invocation that the manifest
  produces, byte for byte.

The manifest is a *data* structure and it is also written to
``scripts/sandbox-manifest.json``; a test asserts that the file and the code
say exactly the same thing, so they cannot drift apart.

The same hostile order must be blocked by **two** independent isolation
layers, so there are two backends behind one interface:

``DockerBackend``        containers (namespaces + cgroups) — the daily driver;
``MicrosandboxBackend``  ``msb`` microVMs (libkrun/KVM, own kernel) — the
                         stronger demonstration.

``RecordingBackend`` is a test double: it records the calls instead of running
them, so the control-plane logic can be tested without Docker.

**The sandbox is never inside the application process** — that is the
"Blast Radius Zero" requirement of the challenge.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol, Sequence

__all__ = [
    "DockerBackend",
    "MicrosandboxBackend",
    "RecordingBackend",
    "SandboxManifest",
    "SandboxResult",
    "SandboxUnavailable",
    "Volume",
    "default_manifest",
]


class SandboxUnavailable(RuntimeError):
    """Raised when the isolation backend cannot be used on this host."""


@dataclass(frozen=True)
class Volume:
    """One volume of the sandbox.  The manifest has exactly one."""

    source: str
    target: str
    read_only: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {"source": self.source, "target": self.target, "read_only": self.read_only}

    def docker_argument(self) -> str:
        mode = "ro" if self.read_only else "rw"
        return f"{self.source}:{self.target}:{mode}"


@dataclass(frozen=True)
class SandboxManifest:
    """The exact isolation profile of a sandbox.

    Every field is a decision, and every decision is visible in the
    ``docker run`` line it produces.
    """

    image: str = "debian:bookworm-slim"
    network: str = "none"
    memory: str = "256m"
    cpus: str = "1"
    pids_limit: int = 128
    read_only: bool = True
    cap_drop: tuple[str, ...] = ("ALL",)
    security_opt: tuple[str, ...] = ("no-new-privileges",)
    user: str = "65534:65534"          # nobody:nogroup
    workdir: str = "/work"
    tmpfs: tuple[str, ...] = ("/tmp:rw,noexec,nosuid,size=64m",)
    volumes: tuple[Volume, ...] = (
        Volume(source="{workspace}", target="/work", read_only=False),
    )
    env_allowlist: tuple[str, ...] = ()   # empty: the sandbox gets NO variable
    timeout_seconds: int = 30
    destroy_on_exit: bool = True
    entrypoint_script: str = "/work/run-in-sandbox.sh"

    def to_dict(self) -> dict[str, Any]:
        """The manifest as a plain mapping — this is what the JSON file holds."""
        return {
            "image": self.image,
            "network": self.network,
            "memory": self.memory,
            "cpus": self.cpus,
            "pids_limit": self.pids_limit,
            "read_only": self.read_only,
            "cap_drop": list(self.cap_drop),
            "security_opt": list(self.security_opt),
            "user": self.user,
            "workdir": self.workdir,
            "tmpfs": list(self.tmpfs),
            "volumes": [volume.to_dict() for volume in self.volumes],
            "env_allowlist": list(self.env_allowlist),
            "timeout_seconds": self.timeout_seconds,
            "destroy_on_exit": self.destroy_on_exit,
            "entrypoint_script": self.entrypoint_script,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "SandboxManifest":
        volumes = tuple(
            Volume(source=str(v["source"]), target=str(v["target"]),
                   read_only=bool(v.get("read_only", False)))
            for v in data.get("volumes", [])
        )
        return cls(
            image=str(data.get("image", "")),
            network=str(data.get("network", "")),
            memory=str(data.get("memory", "")),
            cpus=str(data.get("cpus", "")),
            pids_limit=int(data.get("pids_limit", 0)),
            read_only=bool(data.get("read_only", False)),
            cap_drop=tuple(data.get("cap_drop", ())),
            security_opt=tuple(data.get("security_opt", ())),
            user=str(data.get("user", "")),
            workdir=str(data.get("workdir", "")),
            tmpfs=tuple(data.get("tmpfs", ())),
            volumes=volumes,
            env_allowlist=tuple(data.get("env_allowlist", ())),
            timeout_seconds=int(data.get("timeout_seconds", 0)),
            destroy_on_exit=bool(data.get("destroy_on_exit", False)),
            entrypoint_script=str(data.get("entrypoint_script", "")),
        )


def default_manifest() -> SandboxManifest:
    """The manifest used by the control plane."""
    return SandboxManifest()


@dataclass
class SandboxResult:
    """What came out of the sandbox."""

    exit_code: int
    stdout: str = ""
    stderr: str = ""
    duration_seconds: float = 0.0
    sandbox_id: str = ""

    @property
    def ok(self) -> bool:
        return self.exit_code == 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "exit_code": self.exit_code,
            "stdout": self.stdout,
            "stderr": self.stderr,
            "duration_seconds": self.duration_seconds,
            "sandbox_id": self.sandbox_id,
        }


class SandboxBackend(Protocol):
    """The interface both isolation layers implement."""

    name: str

    def run(self, manifest: SandboxManifest, command: Sequence[str],
            workspace: Path, sandbox_id: str) -> SandboxResult:
        """Run ``command`` in a fresh sandbox and return what it produced."""


class DockerBackend:
    """Runs the order in a Docker container.

    The container is created **fresh** for every order and destroyed with
    ``--rm`` when it exits, whatever the result.
    """

    name = "docker"

    def __init__(self, docker_binary: str = "docker") -> None:
        self.docker_binary = docker_binary

    def available(self) -> bool:
        return shutil.which(self.docker_binary) is not None

    def build_command(
        self,
        manifest: SandboxManifest,
        command: Sequence[str],
        workspace: Path,
        sandbox_id: str,
    ) -> list[str]:
        """The exact ``docker run`` line — this is the artefact under test."""
        args: list[str] = [self.docker_binary, "run", "--rm"]
        args.append(f"--name={sandbox_id}")
        args.append(f"--network={manifest.network}")
        args.append(f"--memory={manifest.memory}")
        args.append(f"--cpus={manifest.cpus}")
        args.append(f"--pids-limit={manifest.pids_limit}")
        if manifest.read_only:
            args.append("--read-only")
        for capability in manifest.cap_drop:
            args.append(f"--cap-drop={capability}")
        for option in manifest.security_opt:
            args.append(f"--security-opt={option}")
        args.append(f"--user={manifest.user}")
        for tmpfs in manifest.tmpfs:
            args.append(f"--tmpfs={tmpfs}")
        for volume in manifest.volumes:
            args.append("--volume")
            args.append(volume.docker_argument().replace("{workspace}", str(workspace)))
        args.append(f"--workdir={manifest.workdir}")
        # No --env, no --env-file: the sandbox inherits nothing from the host.
        args.append(manifest.image)
        args.append(manifest.entrypoint_script)
        args.extend(command)
        return args

    def run(
        self,
        manifest: SandboxManifest,
        command: Sequence[str],
        workspace: Path,
        sandbox_id: str,
    ) -> SandboxResult:
        if not self.available():
            raise SandboxUnavailable(
                "docker is not installed on this host: the sandbox cannot be run"
            )
        args = self.build_command(manifest, command, workspace, sandbox_id)
        started = time.monotonic()
        try:
            completed = subprocess.run(  # noqa: S603 - the argv is built above
                args,
                capture_output=True,
                text=True,
                timeout=manifest.timeout_seconds,
                cwd=str(workspace),
            )
        except subprocess.TimeoutExpired:
            return SandboxResult(
                exit_code=124,
                stdout="",
                stderr=f"the sandbox was killed after {manifest.timeout_seconds}s",
                duration_seconds=time.monotonic() - started,
                sandbox_id=sandbox_id,
            )
        return SandboxResult(
            exit_code=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
            duration_seconds=time.monotonic() - started,
            sandbox_id=sandbox_id,
        )


class MicrosandboxBackend:
    """Runs the order in a ``msb`` microVM (libkrun/KVM, its own kernel).

    The microsandbox guide of Vultr describes exactly this backend: a
    throwaway microVM with explicit ``--cpus`` and ``--memory``, isolated from
    its siblings by default.  Same manifest, same journal, same verdicts — a
    second, independent isolation layer.

    **Not executable in this container** (no ``msb``, no KVM): the command
    line is built and tested, the backend is not run here.
    """

    name = "microsandbox"

    def __init__(self, msb_binary: str = "msb") -> None:
        self.msb_binary = msb_binary

    def available(self) -> bool:
        return shutil.which(self.msb_binary) is not None

    def build_command(
        self,
        manifest: SandboxManifest,
        command: Sequence[str],
        workspace: Path,
        sandbox_id: str,
    ) -> list[str]:
        args: list[str] = [self.msb_binary, "run", "--name", sandbox_id]
        args.append(f"--cpus={manifest.cpus}")
        args.append(f"--memory={manifest.memory.upper()}")
        args.append("--network=none")
        args.append(f"--user={manifest.user}")
        args.append("--read-only")
        for capability in manifest.cap_drop:
            args.append(f"--cap-drop={capability}")
        args.append(f"--workspace={workspace}")
        args.append(manifest.image)
        args.append(manifest.entrypoint_script)
        args.extend(command)
        return args

    def run(
        self,
        manifest: SandboxManifest,
        command: Sequence[str],
        workspace: Path,
        sandbox_id: str,
    ) -> SandboxResult:
        if not self.available():
            raise SandboxUnavailable(
                "msb (microsandbox) is not installed on this host: "
                "the microVM backend cannot be run"
            )
        args = self.build_command(manifest, command, workspace, sandbox_id)
        started = time.monotonic()
        completed = subprocess.run(  # noqa: S603 - the argv is built above
            args, capture_output=True, text=True,
            timeout=manifest.timeout_seconds, cwd=str(workspace),
        )
        return SandboxResult(
            exit_code=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
            duration_seconds=time.monotonic() - started,
            sandbox_id=sandbox_id,
        )


@dataclass
class RecordingBackend:
    """A test double: it records the calls instead of running them.

    It lets the tests exercise the whole control plane — signature, approval,
    quota, journal — without Docker, and it lets a demo run on a laptop with
    no isolation (the journal then records ``backend: "recording"``, so nobody
    can mistake it for a real run).
    """

    name = "recording"
    calls: list[dict[str, Any]] = field(default_factory=list)
    result: SandboxResult = field(default_factory=lambda: SandboxResult(exit_code=0))

    def available(self) -> bool:
        return True

    def build_command(
        self,
        manifest: SandboxManifest,
        command: Sequence[str],
        workspace: Path,
        sandbox_id: str,
    ) -> list[str]:
        return ["recording", *command]

    def run(
        self,
        manifest: SandboxManifest,
        command: Sequence[str],
        workspace: Path,
        sandbox_id: str,
    ) -> SandboxResult:
        self.calls.append(
            {
                "command": list(command),
                "sandbox_id": sandbox_id,
                "workspace": str(workspace),
                "manifest": manifest.to_dict(),
            }
        )
        return SandboxResult(
            exit_code=self.result.exit_code,
            stdout=self.result.stdout,
            stderr=self.result.stderr,
            duration_seconds=self.result.duration_seconds,
            sandbox_id=sandbox_id,
        )


def write_manifest(path: str | Path, manifest: SandboxManifest | None = None) -> Path:
    """Write the manifest as JSON (used to keep the file and the code in sync)."""
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps((manifest or default_manifest()).to_dict(), indent=2,
                   sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return target
