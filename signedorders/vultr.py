"""The Vultr Serverless Inference client — the only place a model is called.

> **NON TESTE — nécessite la clé Vultr du propriétaire.**
> This module was written on 31 October 2026, before the owner's Vultr
> account existed.  The HTTP calls were therefore **never executed**: there
> is no API key in this container, and no call was made.  What *is* tested
> (see ``tests/test_vultr.py``) is everything that does not need the key: the
> URL, the payload, the headers, the absence of the key in the code and in
> the repository, and the behaviour of the client when the key is missing.

The challenge requires Challenge 1 to use **Vultr Serverless Inference** for
the agent's LLM calls, so this client is a required part of the project.

The API is OpenAI-compatible::

    POST https://api.vultrinference.com/v1/chat/completions
    Authorization: Bearer <VULTRINFERENCE_API_KEY>
    {"model": "...", "messages": [...], "temperature": 0.2}

The key is read from the environment and never written to a file, never
logged, and never passed to a sandbox.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any

__all__ = [
    "VULTRINFERENCE_BASE_URL",
    "InferenceError",
    "InferenceResponse",
    "VultrInferenceClient",
    "DEFAULT_MODEL",
]

#: The base URL of the inference endpoint, fixed by the platform.
VULTRINFERENCE_BASE_URL = "https://api.vultrinference.com/v1"

#: The default agent model (validated against the public model list on the
#: build day — the list moves, so it is a configuration value, not a constant
#: of the protocol).
DEFAULT_MODEL = "qwen3.8-flash-next"

#: The name of the environment variable that holds the key.
API_KEY_ENV = "VULTRINFERENCE_API_KEY"


class InferenceError(RuntimeError):
    """Raised when the inference endpoint answers with an error."""


@dataclass
class InferenceResponse:
    """What the endpoint answered."""

    model: str
    content: str
    raw: dict[str, Any] = field(default_factory=dict)
    prompt_tokens: int = 0
    completion_tokens: int = 0

    @property
    def total_tokens(self) -> int:
        return self.prompt_tokens + self.completion_tokens


class VultrInferenceClient:
    """A tiny, dependency-free client for the Vultr Serverless Inference API."""

    def __init__(
        self,
        api_key: str | None = None,
        base_url: str = VULTRINFERENCE_BASE_URL,
        timeout: float = 60.0,
    ) -> None:
        self.api_key = api_key or os.environ.get(API_KEY_ENV) or None
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    # -- the request ------------------------------------------------------- #

    def chat_url(self) -> str:
        """The URL of the chat endpoint."""
        return f"{self.base_url}/chat/completions"

    def models_url(self) -> str:
        """The URL of the model list endpoint."""
        return f"{self.base_url}/models"

    def headers(self) -> dict[str, str]:
        """The headers of a request — the key never leaves this function."""
        if not self.api_key:
            raise InferenceError(
                f"{API_KEY_ENV} is not set: the inference client cannot be used "
                "(the key lives on the Vultr VM, never in this repository)"
            )
        return {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

    def build_payload(
        self,
        messages: list[dict[str, str]],
        model: str = DEFAULT_MODEL,
        temperature: float = 0.2,
        max_tokens: int | None = None,
    ) -> dict[str, Any]:
        """The JSON body of a chat completion request."""
        payload: dict[str, Any] = {
            "model": model,
            "messages": list(messages),
            "temperature": temperature,
        }
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens
        return payload

    # -- the call ---------------------------------------------------------- #

    def chat(
        self,
        messages: list[dict[str, str]],
        model: str = DEFAULT_MODEL,
        temperature: float = 0.2,
        max_tokens: int | None = None,
    ) -> InferenceResponse:
        """Ask the model for a completion.

        **Never executed in this container** — the key does not exist here.
        """
        request = urllib.request.Request(
            self.chat_url(),
            data=json.dumps(
                self.build_payload(messages, model, temperature, max_tokens)
            ).encode("utf-8"),
            headers=self.headers(),
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as answer:
                body = json.loads(answer.read().decode("utf-8"))
        except urllib.error.HTTPError as error:  # pragma: no cover - needs a key
            detail = error.read().decode("utf-8", errors="replace")
            raise InferenceError(
                f"the inference endpoint answered {error.code}: {detail[:300]}"
            ) from None
        except urllib.error.URLError as error:  # pragma: no cover - needs a key
            raise InferenceError(
                f"the inference endpoint is unreachable: {error.reason}"
            ) from None
        return self.parse_response(body)

    def list_models(self) -> list[dict[str, Any]]:  # pragma: no cover - needs a key
        """List the available models (this endpoint is public, but it is not
        called from here: the list was read on 31 October 2026)."""
        request = urllib.request.Request(self.models_url(), method="GET")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as answer:
                body = json.loads(answer.read().decode("utf-8"))
        except urllib.error.URLError as error:
            raise InferenceError(
                f"the model list is unreachable: {error.reason}"
            ) from None
        models = body.get("data", [])
        return models if isinstance(models, list) else []

    # -- parsing ----------------------------------------------------------- #

    @staticmethod
    def parse_response(body: dict[str, Any]) -> InferenceResponse:
        """Turn the raw answer into an :class:`InferenceResponse`."""
        choices = body.get("choices") or []
        content = ""
        if choices:
            message = choices[0].get("message") or {}
            content = str(message.get("content", ""))
        usage = body.get("usage") or {}
        return InferenceResponse(
            model=str(body.get("model", "")),
            content=content,
            raw=body,
            prompt_tokens=int(usage.get("prompt_tokens", 0)),
            completion_tokens=int(usage.get("completion_tokens", 0)),
        )
