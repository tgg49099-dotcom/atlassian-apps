# Web UI

The UI is served by the same FastAPI process as the API (it *is* the control
plane), and it is the only thing the demo video shows — no SSH session, no IP
address, no terminal on the host.

## Panels

| Panel | Content |
|---|---|
| **Mission** | the mission text handed to the agent |
| **Orders** | every order, its action, its target, its risk class and its verdict |
| **Approval queue** | the high-risk orders waiting for a human, with the exact action, target, justification and impact — `APPROVE` / `REFUSE` |
| **Quotas** | the live counters (orders, CPU-seconds, bytes, tokens, concurrent sandboxes) |
| **Journal** | the hash-chained record, with a `verify` button (no secret) |
| **Terminal** | the sandbox output, streamed from the API (never a shell on the host) |

## Implementation

* FastAPI + Jinja2 templates, vanilla JavaScript, no build step.
* Live updates over Server-Sent Events (`GET /stream`).
* The UI never holds a secret: it shows the public key and the verdicts, never
  the signing key nor the inference key.

TODO(build-window): the templates, the SSE wiring, the approval buttons and the
responsive layout for the video recording.
