"""The Vultr Serverless Inference client.

> **NON TESTE — nécessite la clé Vultr du propriétaire.**
> The HTTP calls were never executed: there is no Vultr account yet.  What is
> tested here is everything that does not need the key (the URL, the payload,
> the headers, the parsing) and the fact that the key is nowhere in the
> repository.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from signedorders.vultr import (
    API_KEY_ENV,
    VULTRINFERENCE_BASE_URL,
    InferenceError,
    VultrInferenceClient,
)

ROOT = Path(__file__).resolve().parent.parent

#: A placeholder that is obviously not a key (and that the secret scanner
#: does not mistake for one).
FAKE_KEY = "not-a-real-key-placeholder"


# --------------------------------------------------------------------------- #
# The request
# --------------------------------------------------------------------------- #


def test_the_base_url_is_the_one_of_the_platform():
    assert VULTRINFERENCE_BASE_URL == "https://api.vultrinference.com/v1"


def test_the_chat_url_is_the_openai_compatible_one():
    client = VultrInferenceClient(api_key=FAKE_KEY)
    assert client.chat_url() == "https://api.vultrinference.com/v1/chat/completions"


def test_the_models_url():
    client = VultrInferenceClient(api_key=FAKE_KEY)
    assert client.models_url() == "https://api.vultrinference.com/v1/models"


def test_the_payload_is_the_openai_shape():
    client = VultrInferenceClient(api_key=FAKE_KEY)
    payload = client.build_payload([{"role": "user", "content": "hello"}])
    assert payload["model"]
    assert payload["messages"] == [{"role": "user", "content": "hello"}]
    assert "temperature" in payload


def test_the_payload_can_carry_a_token_limit():
    client = VultrInferenceClient(api_key=FAKE_KEY)
    payload = client.build_payload([], max_tokens=128)
    assert payload["max_tokens"] == 128


def test_the_key_is_sent_as_a_bearer_token():
    client = VultrInferenceClient(api_key=FAKE_KEY)
    headers = client.headers()
    assert headers["Authorization"] == f"Bearer {FAKE_KEY}"
    assert headers["Content-Type"] == "application/json"


def test_without_a_key_the_client_refuses_to_build_the_headers(monkeypatch):
    monkeypatch.delenv(API_KEY_ENV, raising=False)
    client = VultrInferenceClient(api_key=None)
    with pytest.raises(InferenceError) as error:
        client.headers()
    assert API_KEY_ENV in str(error.value)


def test_the_key_is_read_from_the_environment(monkeypatch):
    monkeypatch.setenv(API_KEY_ENV, FAKE_KEY)
    client = VultrInferenceClient()
    assert client.headers()["Authorization"] == f"Bearer {FAKE_KEY}"


# --------------------------------------------------------------------------- #
# The answer
# --------------------------------------------------------------------------- #


def test_the_answer_is_parsed():
    body = {
        "model": "qwen3.8-flash-next",
        "choices": [{"message": {"role": "assistant", "content": "42"}}],
        "usage": {"prompt_tokens": 12, "completion_tokens": 3},
    }
    answer = VultrInferenceClient.parse_response(body)
    assert answer.content == "42"
    assert answer.model == "qwen3.8-flash-next"
    assert answer.prompt_tokens == 12
    assert answer.total_tokens == 15


def test_an_empty_answer_does_not_crash():
    answer = VultrInferenceClient.parse_response({})
    assert answer.content == ""
    assert answer.total_tokens == 0


# --------------------------------------------------------------------------- #
# The hygiene of the repository
# --------------------------------------------------------------------------- #


def test_no_key_is_hardcoded_in_the_package():
    """The key must come from the environment, never from the source."""
    for path in (ROOT / "signedorders").glob("*.py"):
        source = path.read_text(encoding="utf-8")
        assert "Bearer sk-" not in source
        assert not re.search(r"vultr_[A-Za-z0-9]{16,}", source)


def test_the_repository_has_no_key_file():
    for name in (".env", "signing.key", "id_ed25519"):
        assert not (ROOT / name).exists()


def test_the_env_example_only_names_the_variables():
    example = (ROOT / ".env.example").read_text(encoding="utf-8")
    for line in example.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, _, value = line.partition("=")
        assert name.isupper()
        assert value == "", f"the example must not carry a value for {name}"
