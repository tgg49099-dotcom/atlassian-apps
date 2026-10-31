"""The agent worker — the loop that talks to the model.

The worker is the only component that calls **Vultr Serverless Inference**
(``https://api.vultrinference.com/v1``, OpenAI-compatible). It runs the
mission in a loop:

1. send the mission + the journal so far to the model;
2. the model answers with a tool call (``read``, ``write``, ``exec``, …);
3. the worker turns it into an **order request** and hands it to the control
   plane — it never executes anything itself;
4. the result (or the refusal) goes back to the model, which retries.

That is the whole point: the model can *ask* for anything, but only what the
control plane signed can *run*.

TODO(build-window): the HTTP client, the tool-calling loop, the retry policy
and the token accounting (the token spend is charged to the task quota).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

#: The OpenAI-compatible base URL fixed by the platform.
VULTRINFERENCE_BASE_URL = "https://api.vultrinference.com/v1"

#: The tools the model may call. Each one maps to an order action.
TOOLS: tuple[dict[str, Any], ...] = (
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read a file inside the sandbox.",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string"}},
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "Write a file inside the sandbox.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                    "content": {"type": "string"},
                },
                "required": ["path", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_command",
            "description": "Run a command inside the sandbox.",
            "parameters": {
                "type": "object",
                "properties": {"command": {"type": "string"}},
                "required": ["command"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "apply_patch",
            "description": "Apply a patch to the target service.",
            "parameters": {
                "type": "object",
                "properties": {"patch": {"type": "string"}},
                "required": ["patch"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "check_health",
            "description": "Check the health endpoint of the target service.",
            "parameters": {
                "type": "object",
                "properties": {"url": {"type": "string"}},
                "required": ["url"],
            },
        },
    },
)


@dataclass
class AgentWorker:
    """The model loop. It proposes; it never executes."""

    task_id: str
    agent_id: str
    model: str = "qwen3.8-flash-next"
    base_url: str = VULTRINFERENCE_BASE_URL
    api_key: str = ""          # read from the environment, never stored here
    max_steps: int = 12

    # -- the loop ---------------------------------------------------------- #

    def run(self, mission: str) -> dict[str, Any]:
        """Run the mission until the model stops or ``max_steps`` is reached.

        Returns a summary: the steps, the tokens spent, and the final status.
        """
        raise NotImplementedError("TODO(build-window)")

    def _chat(self, messages: list[dict[str, Any]]) -> dict[str, Any]:
        """One call to the Vultr Serverless Inference endpoint."""
        raise NotImplementedError("TODO(build-window)")

    # -- helpers ----------------------------------------------------------- #

    @staticmethod
    def tool_to_action(name: str) -> str:
        """Map a tool name to the order action it corresponds to."""
        return {
            "read_file": "read",
            "write_file": "write",
            "run_command": "exec",
            "apply_patch": "apply",
            "check_health": "read",
        }.get(name, "read")


__all__ = ["TOOLS", "VULTRINFERENCE_BASE_URL", "AgentWorker"]
