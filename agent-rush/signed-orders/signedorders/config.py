"""Configuration — read from the environment, never from a file in the repo.

The names of the variables are documented in ``.env.example``.  Two rules:

* the control plane's own secrets (inference key, signing key path) are
  **never** passed to a sandbox;
* a missing value is an error at start-up, not a silent default.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field

from .quotas import QuotaLimits
from .vultr import VULTRINFERENCE_BASE_URL

__all__ = ["VULTRINFERENCE_BASE_URL", "Settings", "settings_from_env"]


def _env(name: str, default: str | None = None) -> str | None:
    """Read a variable; an empty value falls back to the default."""
    value = os.environ.get(name)
    if value is None or value == "":
        return default
    return value


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass
class Settings:
    """The whole configuration of the control plane."""

    # -- inference --------------------------------------------------------- #
    inference_base_url: str = VULTRINFERENCE_BASE_URL
    inference_api_key: str | None = field(
        default_factory=lambda: _env("VULTRINFERENCE_API_KEY")
    )
    model: str = field(
        default_factory=lambda: _env("VULTRINFERENCE_MODEL", "qwen3.8-flash-next")
    )
    vision_model: str = field(
        default_factory=lambda: _env("VULTRINFERENCE_VISION_MODEL", "qwen3.8-27b")
    )

    # -- signing ----------------------------------------------------------- #
    signing_key_path: str = field(
        default_factory=lambda: _env(
            "SIGNEDORDERS_SIGNING_KEY_PATH", "/etc/signedorders/signing.key"
        )
    )
    approval_key_path: str = field(
        default_factory=lambda: _env(
            "SIGNEDORDERS_APPROVAL_KEY_PATH", "/etc/signedorders/approval.key"
        )
    )

    # -- the HTTP door ----------------------------------------------------- #
    host: str = field(default_factory=lambda: _env("SIGNEDORDERS_HOST", "127.0.0.1"))
    port: int = field(default_factory=lambda: _env_int("SIGNEDORDERS_PORT", 8000))
    journal_path: str = field(
        default_factory=lambda: _env(
            "SIGNEDORDERS_JOURNAL_PATH", "/var/lib/signedorders/journal.jsonl"
        )
    )
    agent_key: str | None = field(
        default_factory=lambda: _env("SIGNEDORDERS_AGENT_KEY")
    )
    operator_key: str | None = field(
        default_factory=lambda: _env("SIGNEDORDERS_OPERATOR_KEY")
    )

    # -- sandbox ----------------------------------------------------------- #
    sandbox_backend: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_SANDBOX_BACKEND", "docker")
    )
    sandbox_image: str = field(
        default_factory=lambda: _env(
            "SIGNEDORDERS_SANDBOX_IMAGE", "debian:bookworm-slim"
        )
    )
    sandbox_memory: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_SANDBOX_MEMORY", "256m")
    )
    sandbox_cpus: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_SANDBOX_CPUS", "1")
    )
    sandbox_pids_limit: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_SANDBOX_PIDS_LIMIT", 128)
    )
    sandbox_timeout_seconds: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_SANDBOX_TIMEOUT_SECONDS", 30)
    )
    egress_allowlist: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_SANDBOX_EGRESS_ALLOWLIST", "")
    )

    # -- quotas ------------------------------------------------------------ #
    quota_orders_per_window: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_QUOTA_ORDERS_PER_WINDOW", 10)
    )
    quota_window_seconds: float = field(
        default_factory=lambda: float(
            _env_int("SIGNEDORDERS_QUOTA_WINDOW_SECONDS", 60)
        )
    )
    quota_orders_per_task: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_QUOTA_ORDERS_PER_TASK", 40)
    )
    quota_cpu_seconds_per_task: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_QUOTA_CPU_SECONDS_PER_TASK", 120)
    )
    quota_bytes_written_per_task: int = field(
        default_factory=lambda: _env_int(
            "SIGNEDORDERS_QUOTA_BYTES_WRITTEN_PER_TASK", 1_048_576
        )
    )
    quota_concurrent_sandboxes: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_QUOTA_CONCURRENT_SANDBOXES", 2)
    )
    quota_tokens_per_task: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_QUOTA_TOKENS_PER_TASK", 200_000)
    )

    # -- approvals --------------------------------------------------------- #
    approval_channel: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_APPROVAL_CHANNEL", "web")
    )
    approval_ttl_seconds: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_APPROVAL_TTL_SECONDS", 900)
    )

    def missing_secrets(self) -> list[str]:
        """The values that must be present before the control plane starts."""
        missing: list[str] = []
        if not self.inference_api_key:
            missing.append("VULTRINFERENCE_API_KEY")
        return missing

    def quota_limits(self) -> QuotaLimits:
        """The quota limits described by this configuration."""
        return QuotaLimits(
            orders_per_window=self.quota_orders_per_window,
            window_seconds=self.quota_window_seconds,
            orders_per_task=self.quota_orders_per_task,
            cpu_seconds_per_task=self.quota_cpu_seconds_per_task,
            bytes_written_per_task=self.quota_bytes_written_per_task,
            concurrent_sandboxes=self.quota_concurrent_sandboxes,
            tokens_per_task=self.quota_tokens_per_task,
        )

    def egress_allowlist_hosts(self) -> tuple[str, ...]:
        """The hosts the sandbox may reach, as a tuple."""
        raw = self.egress_allowlist.strip()
        if not raw:
            return ()
        return tuple(host.strip() for host in raw.split(",") if host.strip())


def settings_from_env() -> Settings:
    """Read the configuration from the environment."""
    return Settings()
