"""Configuration — read from the environment, never from a file in the repo.

The names of the variables are documented in ``.env.example``. Two rules:

* the control plane's own secrets (inference key, Vultr key, signing key path)
  are **never** passed to a sandbox;
* a missing value is an error at start-up, not a silent default.

TODO(build-window): validation of the environment at start-up.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field

#: The base URL of the inference endpoint is fixed by the platform.
VULTRINFERENCE_BASE_URL = "https://api.vultrinference.com/v1"


def _env(name: str, default: str | None = None) -> str | None:
    return os.environ.get(name, default)


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

    # inference
    inference_base_url: str = VULTRINFERENCE_BASE_URL
    inference_api_key: str | None = field(
        default_factory=lambda: _env("VULTRINFERENCE_API_KEY"))
    model: str = field(
        default_factory=lambda: _env("VULTRINFERENCE_MODEL", "qwen3.8-flash-next"))
    vision_model: str = field(
        default_factory=lambda: _env("VULTRINFERENCE_VISION_MODEL", "qwen3.8-27b"))

    # signing
    signing_key_path: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_SIGNING_KEY_PATH",
                                     "/etc/signedorders/signing.key"))
    public_key_path: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_PUBLIC_KEY_PATH",
                                     "./keys/signing.pub"))

    # sandbox
    sandbox_backend: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_SANDBOX_BACKEND", "docker"))
    sandbox_image: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_SANDBOX_IMAGE",
                                     "debian:bookworm-slim"))
    sandbox_memory: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_SANDBOX_MEMORY", "256m"))
    sandbox_cpus: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_SANDBOX_CPUS", "1"))
    sandbox_pids_limit: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_SANDBOX_PIDS_LIMIT", 128))
    sandbox_timeout_seconds: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_SANDBOX_TIMEOUT_SECONDS", 30))
    egress_allowlist: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_SANDBOX_EGRESS_ALLOWLIST", ""))

    # quotas
    quota_orders_per_minute: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_QUOTA_ORDERS_PER_MINUTE", 10))
    quota_orders_per_task: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_QUOTA_ORDERS_PER_TASK", 40))
    quota_cpu_seconds_per_task: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_QUOTA_CPU_SECONDS_PER_TASK", 120))
    quota_bytes_written_per_task: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_QUOTA_BYTES_WRITTEN_PER_TASK", 1_048_576))
    quota_concurrent_sandboxes: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_QUOTA_CONCURRENT_SANDBOXES", 2))
    quota_tokens_per_task: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_QUOTA_TOKENS_PER_TASK", 200_000))

    # approvals
    approval_channel: str = field(
        default_factory=lambda: _env("SIGNEDORDERS_APPROVAL_CHANNEL", "web"))
    approval_ttl_seconds: int = field(
        default_factory=lambda: _env_int("SIGNEDORDERS_APPROVAL_TTL_SECONDS", 900))

    def missing_secrets(self) -> list[str]:
        """The values that must be present before the control plane starts."""
        missing: list[str] = []
        if not self.inference_api_key:
            missing.append("VULTRINFERENCE_API_KEY")
        return missing


__all__ = ["VULTRINFERENCE_BASE_URL", "Settings"]
