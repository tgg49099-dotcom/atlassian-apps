# Signed Orders

**A control plane that turns every privileged action of an autonomous agent into a written,
signed, verifiable order — low-risk orders execute on their own, high-risk orders wait for a
human — all inside an isolated sandbox, with quotas, a hash-chained journal and replay.**

Built for the **Vultr: Agent Rush Hackathon** (3–8 November 2026), challenge **01 — Blast
Radius Zero**.

> *Containment-first is required. An agent that only chats is a demo; an agent that executes
> safely is a product.*

---

## What is in this repository

| Path | What it is |
|---|---|
| `signedorders/policy.py` | the **control plan**: named, ordered, auditable risk rules — no model in the decision loop |
| `signedorders/quotas.py` | the **quotas**: a sliding window per agent, budgets per task, injectable clock |
| `signedorders/ed25519.py` | Ed25519 (RFC 8032) in **pure Python** — the journal is verifiable with no dependency |
| `signedorders/signer.py` | sign / verify an order, an approval, a journal record |
| `signedorders/journal.py` | the **hash-chained** journal: append-only, verifiable offline, replayable |
| `signedorders/control.py` | the control plane: request → verdict → sign *or* queue for a human → execute |
| `signedorders/server.py` | the **HTTP API** — the only public surface of the control plane |
| `signedorders/sandbox.py` | the **manifest** of the isolation and the exact `docker run` line it produces |
| `scripts/run-in-sandbox.sh` | the script that runs **inside** the fresh sandbox |
| `scripts/sandbox-manifest.json` | the manifest, published as data (a test keeps it in sync with the code) |
| `scripts/demo-containment.sh` | **the containment moment, reproducible in one command** — no account, no key |
| `deploy/cloud-init.yml` | the cloud-init that installs and starts everything on a Vultr Ubuntu 24.04 VM |
| `deploy/verifier-deploiement.sh` | the script that proves a deployment answers (run it on the VM, or from anywhere) |
| `signedorders/vultr.py` | the **Vultr Serverless Inference** client — the only place a model is called |
| `signedorders/replay.py` | re-derive the verdicts of a journal with the rules of today |
| `signedorders/preflight.py` | the secret scanner that runs before every commit |
| `tests/` | **218 tests — 217 passed, 1 skipped** on a bare clone (the 218th cross-checks the pure-Python Ed25519 against the optional `cryptography` library), including the hostile ones |
| `slides/` | the slide deck (PDF) required by the challenge |

**The core has no third-party dependency.** `python3 -m signedorders verify journal.jsonl`
works on a bare Python 3.10+, with nothing installed.

**Status of the code, stated plainly.** The control plane — the policy, the quotas, the
signatures, the journal, the sandbox manifests, the HTTP API — is **written and tested**
(218 tests: 217 passed, 1 skipped). Two parts are **written but not yet executed**: the Docker/Microsandbox
isolation (this container has neither Docker nor KVM) and the Vultr Serverless Inference
client (it needs the owner's key). The agent loop that drives the model is **planned for
the build window (3 → 8 November)** and is not in the repository yet; what is in the
repository is the part that keeps the agent contained.

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

---

## 1. The control plan — named, auditable rules

The decision that makes an order executable must be **reproducible, diffable and reviewable**,
so it is made by a table of rules, not by a model. `signedorders/policy.py` imports nothing
but the standard library (a test asserts it), and every rule has an id, a one-sentence
explanation and a risk level.

The **first matching rule wins**, and the forbidden rules come first on purpose.

| Rule | Risk | What it catches |
|---|---|---|
| `forbidden.escape` | forbidden | the target reaches the host (`host://`, an absolute path) or walks out of the sandbox (`..`) |
| `forbidden.privilege_escalation` | forbidden | `--privileged`, `sudo`, `chroot`, `mount`, `nsenter`, `unshare` |
| `forbidden.secret_read` | forbidden | a private key, an `.env` file, `printenv`, `/proc/self/environ` |
| `forbidden.cloud_metadata` | forbidden | `169.254.169.254` and friends — the usual way to steal a credential |
| `risk.destructive` | **high** | `rm`, `truncate`, `DROP TABLE`, `git reset --hard`, `systemctl stop` |
| `risk.network` | **high** | any outbound call to a host that is not on the egress allowlist |
| `risk.mutating_target` | medium | `apply` / `restart` on the target service — snapshot + rollback armed |
| `allow.sandbox_read` / `allow.sandbox_write` / `allow.sandbox_test` / `allow.sandbox_exec` | low | the work itself, inside a sandbox that is thrown away afterwards |
| `allow.escalate` | low | the agent asking a human a question |
| `default.unknown` | **high** | anything else: the conservative default |

`forbidden` and `high` are **not** the same refusal:

* `high` → the order waits in the **human queue**; a human may approve it, with a **signed**
  approval, or reject it; if nobody answers within the TTL, the order **expires** (silence is
  not an approval);
* `forbidden` → the order is refused and **no human approval can ever unlock it**.

Every decision carries a **trace** — the rule that fired, the input it was given and the
output it produced:

```json
{
  "rule_id": "risk.destructive",
  "explanation": "The order deletes or overwrites something, so it waits for a signed human approval.",
  "inputs":  {"action": "exec", "target": "sandbox://sb-42:/app/run.sh", "args": {"command": "rm -rf /srv/data"}},
  "outputs": {"risk": "high", "rule_id": "risk.destructive"},
  "evaluated": ["forbidden.escape", "forbidden.privilege_escalation", "..."],
  "consulted": ["action", "args"]
}
```

Print the whole table yourself:

```bash
python3 -m signedorders policy
```

---

## 2. The quotas — a sliding window, and an injectable clock

| Limit | Default | Scope |
|---|---|---|
| `orders_per_window` | 10 | per agent, sliding window of 60 s |
| `orders_per_task` | 40 | per task |
| `cpu_seconds_per_task` | 120 | per task |
| `bytes_written_per_task` | 1 MiB | per task |
| `tokens_per_task` | 200 000 | per task (the LLM cost is capped too) |
| `concurrent_sandboxes` | 2 | per task — a **safety** limit: breaking it triggers the rollback |

The window **slides**: an order that leaves the window frees its slot immediately, it is not a
bucket that refills on a wall-clock boundary. The clock is injected, so the tests never sleep:

```python
clock = FakeClock(0.0)
quotas = QuotaManager(clock=clock)
quotas.charge_order("agent-1")          # 1 order in the window
clock.advance(60.0)                     # the window has moved
quotas.orders_in_window("agent-1")      # 0
```

A broken quota is a **refusal**, not a warning — and, unlike the policy, no human approval can
unlock it.

---

## 3. The signature — Ed25519, in pure Python

`signedorders/ed25519.py` implements RFC 8032 in ~150 lines of readable Python, so the whole
"verify it yourself, offline, with no secret" story works on a machine where nothing is
installed. The tests cross-check it byte for byte against the `cryptography` library when it
is installed, and against the RFC 8032 test vector always.

```bash
python3 -m signedorders init-key /etc/signedorders/signing.key   # mode 0600, never committed
python3 -m signedorders sign order.json                          # prints the envelope
python3 -m signedorders verify journal.jsonl                     # no secret needed
```

The **public** key is committed on purpose; the **private** key lives on the VM.

---

## 4. The journal — a hash chain you can check offline

Every record carries the hash of the previous one:

```
h_n = sha256( h_{n-1} || sha256(canonical_json(record_n)) )
```

`verify()` re-computes the chain from scratch and says **what** is broken and **where**:

| Attack | What the report says |
|---|---|
| a record was edited | *"entry 1 was modified after it was written: the recomputed hash is 3f9a…, the file says 7b21…"* |
| a record was removed or inserted | *"the records are not a contiguous sequence (entry 2 has the index 3): a record was removed or inserted"* |
| the records were reordered | *"entry 1 does not chain on the previous one … (the records were reordered)"* |

`replay` reads the journal back and **re-derives every verdict with the rules of today**: if
the policy changed after the fact, the replay says so, with the order id and both verdicts.

---

## 5. The containment moment

This is the real, reproducible transcript of `bash scripts/demo-containment.sh` — one command,
no account, no key, nothing to install:

| Step | The order | Class | The answer |
|---|---|---|---|
| 1 | `exec pytest -q` in `sandbox://sb-demo` | `allow.sandbox_exec` → low | **HTTP 201** — `auto_execute`, signed and executed |
| 2 | `delete sandbox://sb-demo:/srv/data` (`rm -rf /srv/data`) | `risk.destructive` → high | **HTTP 201** — `queued_for_human`: it **waits**, it does not run |
| 3 | the same order, after the operator answers | — | **HTTP 200** — `rejected`, a **signed** refusal on the record |
| 4 | `read host:///etc/app/secret` | `forbidden.escape` → forbidden | **HTTP 403** — `refused`, and **no human approval can ever unlock it** |
| 5 | the journal | — | verified live, then verified **offline** with no secret: *the chain is intact* |

> *The blast radius was one order that never ran.*

And even when a human *approves*, the sandbox absorbs it: `rm -rf /` kills the container, not
the server; an infinite loop is killed by a wall-clock timer; a memory bomb is killed by the
256 MB cgroup.

---

## 6. The sandbox — the exact isolation

The manifest is published as data (`scripts/sandbox-manifest.json`) and a test asserts that it
is **exactly** what the code builds. The command line it produces is:

```
docker run --rm --name=sb-… --network=none --memory=256m --cpus=1 --pids-limit=128
  --read-only --cap-drop=ALL --security-opt=no-new-privileges --user=65534:65534
  --tmpfs=/tmp:rw,noexec,nosuid,size=64m --volume <workspace>:/work:rw --workdir=/work
  debian:bookworm-slim /work/run-in-sandbox.sh pytest -q
```

| Concern | How it is handled |
|---|---|
| Process isolation | The sandbox never runs inside the application process. The agent holds no shell. |
| Secret hygiene | The sandbox is started with **no environment variable at all** — no `--env`, no `--env-file`. The inference key and the Vultr API key live only in the control-plane process. |
| Resource limits | `--memory=256m --cpus=1 --pids-limit=128 --read-only --cap-drop=ALL`, plus a wall-clock timeout inside the container. |
| Lifecycle discipline | A brand-new sandbox per task, destroyed with `--rm` when it exits. |
| Egress | `--network=none`; an explicit allowlist for the few hosts that are genuinely needed. |
| Isolation layers | Two interchangeable backends: **Docker** (namespaces + cgroups) and **Microsandbox** (`msb`, microVMs on libkrun/KVM with their own kernel). The same hostile order is blocked by both. |

**What was and was not run here.** The repository was written in a container **without Docker
and without KVM**, so the isolation itself was not executed at this stage: the command line,
the manifest and the prohibitions are covered by tests, and the container run happens on the
Vultr VM during the build window. The Vultr Serverless Inference client is likewise written
but **not executed** — it needs the owner's key. Both facts are stated in the code, not hidden.

---

## How Vultr powers this

> *Required by the challenge brief: “In your public GitHub README, explain the Vultr services
> and infrastructure used.”*

| Vultr service | How Signed Orders uses it |
|---|---|
| **Vultr Cloud Compute** (Ubuntu 24.04 VM) | The control plane **is** the backend: the HTTP API, the Ed25519 key, the hash-chained journal and the two sandbox backends all run on one Vultr VM. The API is served from that VM (see the demo URL in the submission). |
| **Vultr Serverless Inference** (`https://api.vultrinference.com/v1`, OpenAI-compatible) | The **only** place a model is called: the agent loop. Required by the challenge for Challenge 1. Two roles: a fast text model for the agent loop, and a **vision** model that verifies the screenshot of the repaired service. The key (`VULTRINFERENCE_API_KEY`) is read from the environment of the control-plane process, never written to a file, never logged, and never passed to a sandbox. |
| **Agent sandboxing on Vultr** (Docker / Microsandbox) | Every order runs in a container (namespaces + cgroups) or in an `msb` microVM with its own kernel, created fresh per task and destroyed at the end. The sandbox is *never* inside the application process. |
| **Vultr API** (*not used by the current code*) | The challenge lists it as one of the ways to create *throwaway instances*. This project does **not** call it: the sandboxes run on the single VM (a Docker container, or an `msb` microVM), which is what the `SandboxBackend` interface implements today. A per-task ephemeral VM would plug into that same interface — it is simply not written yet, and this README does not pretend otherwise. |
| **Hackathon credits** | $200 of free Vultr credits per participant cover the VM and the inference calls for the whole build. The project does not depend on any GPU (none are available for this event). |

Deployment shape (one VM, one URL). The parts marked **(planned)** are the agent loop that
is built during the hackathon window (3 → 8 November); everything else is in this repository
today:

```
                    ┌──────────────────────── Vultr VM (Ubuntu 24.04) ─────────────────────────┐
  browser  ───────▶ │  signedorders (HTTP API)  :8000                                            │
                    │        │                                                             │
                    │        ├── signedorders worker (agent loop → Serverless Inference) (planned)│
                    │        ├── signedorders executor (signed orders only)                 │
                    │        ├── docker / msb sandboxes (one per task, destroyed after)      │
                    │        └── journal.jsonl (hash-chained) + the Ed25519 key              │
                    └──────────────────────────────────────────────────────────────────────────┘
```

### The launch command

```bash
# on the Vultr VM
git clone --branch signed-orders https://github.com/tgg49099-dotcom/atlassian-apps.git signed-orders && cd signed-orders
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"                 # the core itself needs nothing

export VULTRINFERENCE_API_KEY=…        # read from /etc/signedorders/env, mode 0600
export SIGNEDORDERS_SIGNING_KEY_PATH=/etc/signedorders/signing.key

signedorders init-key "$SIGNEDORDERS_SIGNING_KEY_PATH"   # once, on the VM, never committed
signedorders serve --host 0.0.0.0 --port 8000             # the HTTP API on :8000
signedorders verify /var/lib/signedorders/journal.jsonl   # offline verification, no secret
```

The whole VM is built by one cloud-init file (`deploy/cloud-init.yml`): it installs the
packages, clones the project, generates the signing key and the two bearer keys **on the
VM**, installs the systemd unit and starts the service. The full procedure, with the
commands, is in [`DEPLOIEMENT-VULTR.md`](submission/DEPLOIEMENT-VULTR.md) and in
[`deploy/`](deploy/).

The quotas are read from the environment too (`SIGNEDORDERS_QUOTA_ORDERS_PER_WINDOW=10`,
`SIGNEDORDERS_QUOTA_WINDOW_SECONDS=60`, `SIGNEDORDERS_QUOTA_ORDERS_PER_TASK=40`,
`SIGNEDORDERS_QUOTA_CPU_SECONDS_PER_TASK=120`,
`SIGNEDORDERS_QUOTA_BYTES_WRITTEN_PER_TASK=1048576`,
`SIGNEDORDERS_QUOTA_CONCURRENT_SANDBOXES=2`,
`SIGNEDORDERS_QUOTA_TOKENS_PER_TASK=200000`). The names are listed in `.env.example`, with no
value.

---

## Install and run

```bash
# the `signed-orders` branch holds the project alone, at the root of the repository
git clone --branch signed-orders https://github.com/tgg49099-dotcom/atlassian-apps.git signed-orders
cd signed-orders
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
```

> The project also lives in the `main` branch, in the `agent-rush/signed-orders/` directory of
> the same (multi-project) repository — `git clone https://github.com/tgg49099-dotcom/atlassian-apps.git && cd atlassian-apps/agent-rush/signed-orders` works exactly the same.

> In a container image that exports `PIP_USER=1` (some CI and Docker bases do), pip refuses the
> editable install with *« Can not perform a '--user' install. User site-packages are not visible
> in this virtualenv. »* — use `PIP_USER=0 pip install -e ".[dev]"` there. On a normal machine the
> command above is enough.

```bash
pytest                                   # 217 passed, 1 skipped (218 with the reference extra)
python3 -m signedorders policy           # the rule table
python3 -m signedorders preflight .      # the secret scanner
bash scripts/demo-containment.sh         # the containment moment, end to end, in one command
```

The core needs **no dependency**; `pytest` is only needed to run the tests, and
`cryptography` only to cross-check the pure-Python Ed25519 implementation.

---

## Repository layout

```
signed-orders/
├── DESIGN.md              the architecture: data flow, policy, signing, journal
├── README.md              this file
├── LICENSE                MIT
├── .env.example           variable names only, never a value
├── pyproject.toml         dependencies (none) and the console entry point
├── signedorders/
│   ├── canonical.py       the exact bytes that are hashed and signed
│   ├── cli.py             CLI: verify | replay | policy | init-key | sign | preflight | serve
│   ├── config.py          configuration read from the environment
│   ├── control.py         the control plane: request → verdict → sign/queue → execute
│   ├── ed25519.py         RFC 8032 in pure Python
│   ├── journal.py         append-only, hash-chained JSONL + offline verification
│   ├── models.py          Order, Decision, Risk, Action, Verdict, Trace, Approval
│   ├── policy.py          the auditable risk rules (no model in this loop)
│   ├── preflight.py       secret scanner, run before every commit
│   ├── quotas.py          the sliding window and the per-task budgets
│   ├── replay.py          re-derive the verdicts of a journal
│   ├── sandbox.py         the manifest and the two isolation backends
│   ├── server.py          the HTTP API (the only public surface)
│   ├── signer.py          Ed25519 signatures (private key stays on the host)
│   └── vultr.py           the Vultr Serverless Inference client
├── scripts/
│   ├── run-in-sandbox.sh  the script that runs inside the fresh sandbox
│   ├── sandbox-manifest.json  the manifest, published as data
│   └── demo-containment.sh   the containment moment, reproducible in one command
├── deploy/
│   ├── cloud-init.yml     the installation of the Vultr VM, end to end
│   ├── signedorders.service  the systemd unit (the same one the cloud-init writes)
│   └── verifier-deploiement.sh  the script that proves a deployment answers
├── slides/                the slide deck (PDF) required by the challenge
└── tests/                 the specification of the invariants (218 tests: 217 passed, 1 skipped)
```

## Tests

```bash
pytest        # 217 passed, 1 skipped

# the 218th test cross-checks the pure-Python Ed25519 against the
# `cryptography` library: it is skipped when that optional library is absent.
#   pip install -e ".[reference]"   ->  218 passed
```

The suite is written as the specification of the invariants, and the hostile cases are the
point of it:

| Area | What is proved |
|---|---|
| `test_ed25519.py` | the RFC 8032 vector, byte-for-byte equality with `cryptography`, and that a tampered message, a tampered signature, a truncated signature or a foreign key never verifies |
| `test_policy.py` | every rule fires on a hostile order; the forbidden rules come first; the policy imports no model and no network; two identical orders get the same verdict |
| `test_quotas.py` | the window slides, the boundary is exact, the clock is injected, the budgets are enforced, the concurrency limit is a safety limit |
| `test_journal.py` | an edited, removed, inserted or reordered record is detected, and the report says which |
| `test_control.py` | the queue, the signed approval, the forged approval, the expired order, the refused order, the tampered signature |
| `test_sandbox.py` | the required flags are present, the dangerous ones are absent, no environment variable reaches the sandbox, the manifest file matches the code, the script never reaches the network |
| `test_vultr.py` | the URL, the payload, the headers, the parsing — and that no key exists anywhere in the repository |
| `test_replay.py` | the verdicts of a journal are re-derived, and a change of rules is reported |
| `test_cli.py` | what a judge runs: `verify` returns 0 on an intact journal and 1 on a broken one, `serve` starts the HTTP door and the door really answers |

## Hackathon submission

**Vultr: Agent Rush Hackathon** (3–8 November 2026) — challenge **01 — Blast Radius Zero**.

| What the jury asks for | Where it is | State |
|---|---|---|
| « GitHub repository with setup and documentation » | this repository — public, MIT, `pip install -e ".[dev]"`, `pytest`, and the launch command below | **done** |
| « Vultr VM backend deployment » | [`DEPLOIEMENT-VULTR.md`](submission/DEPLOIEMENT-VULTR.md) + [`deploy/cloud-init.yml`](deploy/cloud-init.yml) + [`deploy/verifier-deploiement.sh`](deploy/verifier-deploiement.sh) | **ready to run** — the VM itself is created by the owner (account + payment are a human action) |
| « Public demo URL » | `http://<VM-IP>:8000/health` (and `/policy`, `/quotas`, `/orders`, `/journal/verify`) | **to be filled by the owner** after the VM is up |
| « Recorded demo video » | the script of the shots is in [`DEMO-FILMEE.md`](submission/DEMO-FILMEE.md) | **to be recorded by the owner** |
| « Clear architecture and use-case explanation » | [DESIGN.md](DESIGN.md) and the sections “The idea, in one paragraph” / “How it works” above | **done** |
| « One containment moment shown in the video » | `bash scripts/demo-containment.sh` — the same story, reproducible in one command, no account and no key | **done and reproducible** |
| « In your public GitHub README, explain the Vultr services and infrastructure used » | the section “How Vultr powers this” above | **done** |
| « Submit on lablab.ai » (+ the Discord of the event) | the submission form of the event | **to be done by the owner** (account creation is a human action) |

**The proof, in one command** — no account, no key, no cloud:

```bash
git clone --branch signed-orders https://github.com/tgg49099-dotcom/atlassian-apps.git signed-orders
cd signed-orders
python3 -m venv .venv && . .venv/bin/activate && pip install -e ".[dev]"
pytest                                    # 217 passed, 1 skipped (218 with the reference extra)
bash scripts/demo-containment.sh          # the containment moment, end to end
```

**The public links**

| | |
|---|---|
| Repository (this project) | <https://github.com/tgg49099-dotcom/atlassian-apps/tree/main/agent-rush/signed-orders> |
| Repository, project alone at the root of a branch | <https://github.com/tgg49099-dotcom/atlassian-apps/tree/signed-orders> |
| Slide deck (PDF, 7 pages) | <https://github.com/tgg49099-dotcom/atlassian-apps/blob/signed-orders/slides/signed-orders.pdf> |
| Demo URL | _to be filled by the owner_ |
| Demo video | _to be recorded by the owner_ |

> The repository is a multi-project container: the project lives in the `agent-rush/signed-orders/`
> directory, and the branch `signed-orders` shows the same project alone, at the root of the
> repository, with its MIT `LICENSE`.

---

## Security notes

- No secret, key, token or private address is stored in this repository.
- The Ed25519 **private** key is generated on the host at first start and never committed.
  The **public** key is committed on purpose: it lets anyone verify the journal offline.
- The demo target is a service written for this project. The "secret" the prompt injection
  tries to exfiltrate is a fake (`DEMO_ONLY_NOT_A_REAL_KEY`), and the customer data is
  fictional.
- `preflight` runs before every commit and fails the build on a secret-looking pattern, a
  private key, or an internal address.
- The demo video shows the API and its decisions: no SSH session, no IP address, no
  terminal on the host. The `scripts/demo-containment.sh` transcript is the same story,
  reproducible on any machine.

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgements

Built during the Vultr: Agent Rush Hackathon (3–8 November 2026). Infrastructure and
inference provided by [Vultr](https://www.vultr.com/); the event is run by
[lablab.ai](https://lablab.ai/).
