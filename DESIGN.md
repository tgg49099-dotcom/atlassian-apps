# SIGNED ORDERS — design

> **Challenge 01 — "Blast Radius Zero"** — Vultr: Agent Rush Hackathon (3–8 November 2026).
> This document describes **what is built, how it works, and what is honestly not built yet**.
> The shot list and the narration are in [`submission/DEMO-FILMEE.md`](submission/DEMO-FILMEE.md);
> the deployment procedure is in [`submission/DEPLOIEMENT-VULTR.md`](submission/DEPLOIEMENT-VULTR.md).
>
> **Status of this repository (01/11/2026).** The implementation is published here: `signedorders/`
> (control plane, policy, quotas, hash-chained journal, pure-Python Ed25519, HTTP door, the two
> sandbox backends, the replay and the secret scanner), `tests/` (**217 passed, 1 skipped** on a
> bare clone — 218 with the optional `cryptography` cross-check), `scripts/` (the sandbox manifest
> and its runner, and the one-command containment demo), `deploy/` (the cloud-init that builds the
> Vultr VM and the deployment verifier), `slides/` (the PDF deck) and `submission/` (the four
> documents the jury reads).
>
> The core has **no dependency at all**: `python3 -m signedorders verify <journal>` checks the hash
> chain offline, with no secret and no install. What is **not** exercised yet is the *deployment*:
> running the Docker sandbox and calling the inference endpoint need the owner's Vultr account, key
> and VM. The agent loop (the LLM worker that drives a mission) is **not in this repository yet** —
> section 2 says exactly what is in and what is not, and nothing else is claimed.

---

## 1. The project in one sentence

**A control plane that turns every action of an autonomous agent into a written, signed,
verifiable *order* — ordinary orders go through on their own, dangerous ones wait for a human,
forbidden ones can never be approved at all — with quotas, a hash-chained journal and an
offline replay, all of it executed in a throw-away sandbox.**

> "An agent that only chats is a demo; an agent that executes safely is a product."
> — the event page. That is exactly what this project is.

---

## 2. What the agent does — and what is in this repository **today**

### 2.1 The mission (the scenario the demo will tell)

An agent receives a mission in natural language and has to **execute** something real, in a
sandbox, with a verifiable receipt:

> "The `checkout-api` service of our demo platform does not answer any more. Find the cause, fix
> it, and prove the service is alive again. You are not allowed to touch customer data."

The agent would read the service logs and its code, deduce the cause, act, and end on
**artefacts** — a diff, a test output, an exit code, an `HTTP 200` from the health check, the
fingerprint of the journal — that anybody can re-check. Every read, write and command would go
through a signed order.

### 2.2 What is **already built and tested** in this repository

| Component | File | What it guarantees |
|---|---|---|
| The policy | `signedorders/policy.py` | 14 named, ordered rules classify every order; no model and no network in the decision loop; the same order always gets the same verdict |
| The quotas | `signedorders/quotas.py` | sliding window per agent, budgets per task (orders, CPU seconds, bytes written, tokens, concurrent sandboxes), with an injectable clock |
| The signature | `signedorders/ed25519.py`, `signer.py` | RFC 8032 Ed25519 in pure Python; the private key stays on the host, the public key is published |
| The journal | `signedorders/journal.py` | append-only JSONL, hash-chained, verifiable offline with the standard library only |
| The control plane | `signedorders/control.py` | order → verdict → sign / queue for a human → execute; a signed human approval; expiry; refusal that no human can unlock |
| The HTTP door | `signedorders/server.py` | the only public surface: 5 read routes and 2 write routes, bearer keys compared in constant time |
| The executor | `signedorders/sandbox.py` | one manifest, published as data, and two real isolation backends (Docker containers and `msb` microVMs), plus a recording test double |
| The replay | `signedorders/replay.py` | re-derives the verdict of every recorded decision with the policy of today and reports any mismatch |
| The secret scanner | `signedorders/preflight.py` | fails the build on a secret-looking pattern, a complete private-key block or an internal address |
| The inference client | `signedorders/vultr.py` | the Vultr Serverless Inference client (OpenAI-compatible), used by the agent loop |
| The CLI | `signedorders/cli.py` | `verify`, `replay`, `policy`, `init-key`, `sign`, `preflight`, `serve` |

### 2.3 What is **not** in this repository yet — stated plainly

| Missing | Why it is missing | What replaces it in the demo that exists today |
|---|---|---|
| The agent loop (the LLM worker that turns a mission into orders) | it is written during the build window (3–8 November) | the demo script submits the orders a hostile instruction would produce, by hand |
| The demo target service `checkout-api` (deliberately broken) | same | the demo does not need a broken service to show containment |
| The web UI (orders, verdicts, quota counters, live journal) | same | the same information is served by the read routes of the HTTP door |
| The sandbox replay (re-running recorded orders in a fresh sandbox) | same | the *decision* replay already works and is tested |
| The `msb` microVM backend **at runtime** | the container has no KVM and no `msb`; its command line is built and tested, the backend is not run here | the Docker backend is the daily driver, the microVM backend is the second layer |

Everything above is a **plan**, and the repository never pretends otherwise: the demo script and
the video plan say explicitly that the agent scene is conditional and must be cut if it is not
ready on the day.

---

## 3. Why its action is sandboxed — the core of the project

This is the "Blast Radius Zero" criterion: *"Process isolation: execution never touches the host
or app runtime. Secret hygiene: no API keys or credentials inside the sandbox. Resource limits:
apply time and memory caps to every run. Lifecycle discipline: reset or destroy the environment
after each task."*

**Our answer to those four points — and the heart of the system:**

| Vultr requirement | Our answer | Where it is proved |
|---|---|---|
| Process isolation | The agent **has no shell**. It can only **request** an order at the control plane's API. The executor is a separate process and the order runs in a separate container. | The journal: every agent action is a *request*, never an execution; `tests/test_control.py` |
| Secret hygiene | The sandbox is launched with **no environment variable at all** (no `--env`, no `--env-file`); the keys (inference, agent, operator) exist only in the control-plane process, never in the sandbox | `tests/test_sandbox.py` asserts the absence of every `--env`; the manifest is public data |
| Resource limits | `--memory=256m --cpus=1 --pids-limit=128`, network cut (`--network=none`), read-only root filesystem, **wall-clock timeout** (`timeout --signal=KILL`), plus per-agent and per-task quotas | `signedorders/sandbox.py` builds the line; `tests/test_sandbox.py` asserts the flags; `signedorders/quotas.py` |
| Lifecycle discipline | One **fresh** sandbox per order, destroyed when it exits (`--rm`; `destroy` for the microVM backend). Nothing survives from one task to the next | `signedorders/sandbox.py`; the manifest is published as `scripts/sandbox-manifest.json` and a test asserts it matches the code |

**And the point that makes the difference — the separation of roles:**

```
   AGENT (no shell, no secret)      CONTROL PLANE (on the Vultr VM)        EXECUTOR
   ───────────────────────────      ──────────────────────────────        ──────────
   may: POST /orders ──────────▶    1. identify the agent
   may NOT:                         2. classify the risk (named rules)
    • run a shell                   3. enforce the quotas (sliding window)
    • read a secret                 4. sign (Ed25519) — or queue for a human
    • write outside the sandbox     5. run in a fresh sandbox ────────▶   6. append to the
                                        (the agent never gets a shell)        journal
                                                                              7. destroy the
                                                                                 sandbox
```

**The private signing key never leaves the VM and is never in the repository.** The **public**
key is: from a fresh clone, with no secret at all, anybody can check the journal offline:

```bash
python3 -m signedorders verify journal.jsonl   # RC 0 = the chain is intact
```

**This is the heart of the "Blast Radius Zero" argument:** a compromised agent (by a prompt
injection, a bug, or a hallucinating model) can *ask* for anything; it can only *run* what has
been **signed**, and it can only obtain a signature if the policy classified the order as low
risk. Everything else waits for a human — and the forbidden ones wait for nobody, because no
human approval can unlock them.

**The human gate is short and readable:** a high-risk order appears with the exact action, the
target, the justification and the impact → `APPROVE` / `REFUSE`. If nobody answers, the order
**expires** (silence is not an approval). The approval itself is **signed** with the operator's
key: "a human said yes" is an artefact that can be verified, not a boolean that anybody can flip.

---

## 4. Architecture (what is really deployed)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  VULTR CLOUD COMPUTE — Ubuntu 24.04 — one VM, the control plane               │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │  signedorders/  — Python 3.10+, standard library only                  │  │
│  │   server.py     HTTP door (:8000) — the only public surface            │  │
│  │   control.py    order → verdict → sign / queue → execute                │  │
│  │   policy.py     14 named rules (risk classification — auditable)        │  │
│  │   quotas.py     sliding window per agent + budgets per task             │  │
│  │   journal.py    JSONL append-only, hash-chained, verifiable offline    │  │
│  │   ed25519.py    RFC 8032, pure Python (signing / verification)          │  │
│  │   replay.py     re-derives the verdicts of a journal                     │  │
│  │   preflight.py  the secret scanner                                     │  │
│  │   vultr.py      the Vultr Serverless Inference client                  │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│         ▲  POST /orders  (bearer key of the agent = "ask", nothing else)    │
│         │  POST /orders/<id>/approve|reject  (bearer key of the operator)   │
│  ┌──────┴───────────┐      ┌────────────────────┐      ┌──────────────────┐  │
│  │ agent (planned)  │      │ executor           │      │ sandbox          │  │
│  │ LLM loop →       │      │ checks the         │      │ NEW per order,   │  │
│  │ Vultr Serverless │      │ signature + the    │      │ destroyed after  │  │
│  │ Inference        │      │ policy again,      │      │ --network=none   │  │
│  │ (OpenAI-compat.) │      │ applies the quotas │      │ --memory=256m    │  │
│  └──────────────────┘      └────────────────────┘      │ --cpus=1         │  │
│                            journal.jsonl + the key      │ --read-only      │  │
│                            (mode 0600, on the VM)       │ --cap-drop=ALL   │  │
│                                                       │ --pids-limit=128 │  │
│                                                       │ wall-clock timeout│ │
│                                                       └──────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Two real sandbox backends behind one interface** (`SandboxBackend`):

| Backend | Isolation | Role |
|---|---|---|
| `docker` | container (namespaces + cgroups) | the daily driver (Docker is "strongly recommended" by Vultr) |
| `microsandbox` (`msb`) | **microVM** (libkrun/KVM, its own kernel) | the strong demonstration: *the same hostile order, blocked by two different isolation layers* |

A third backend, `recording`, is a **test double**: it records the calls instead of running
them, so the whole control plane (signature, approval, quota, journal) can be exercised —
and a demo run on a laptop with no isolation at all. It writes `backend: "recording"` in the
journal, so nobody can mistake it for a real run.

> Vultr's "Agent Sandboxing" guide describes exactly `msb`: microVM, explicit `--cpus`/`--memory`,
> isolation between sibling sandboxes by default, Python SDK, MCP server. The command line is
> built and tested here; the backend cannot run in this container (no KVM, no `msb`).

**The API surface — five read routes, two write routes:**

| Route | Method | What it answers |
|---|---|---|
| `/health` | GET | status, **public** key, key id, counters (never a private key, never a secret) |
| `/policy` | GET | the 14 rules, with their id, risk and explanation |
| `/quotas` | GET | the quota counters of an agent and of a task |
| `/orders` | GET | the pending orders and the decisions taken |
| `/journal/verify` | GET | the hash chain, verified **live** |
| `/orders` | POST | submit an order (bearer key of the agent) |
| `/orders/<id>/approve` · `/reject` | POST | a **signed** human decision (bearer key of the operator) |

---

## 5. The stack (what is really used)

| Layer | Choice | Why |
|---|---|---|
| **Hosting** | **Vultr Cloud Compute**, one Ubuntu 24.04 VM | "Both challenges require a Vultr VM backend"; the control plane *is* the backend |
| **Inference (mandatory)** | **Vultr Serverless Inference**, `https://api.vultrinference.com/v1` (OpenAI-compatible) | "Challenge 1 **must** use Vultr Serverless Inference for agent LLM calls" |
| **Language** | Python 3.10+, **standard library only** | the core has **no dependency**: a judge clones and runs it with nothing to install |
| **HTTP door** | `http.server` (`ThreadingHTTPServer`) | no framework to deploy, no version to keep in sync; the door is 400 lines and is tested |
| **Signature** | Ed25519 (RFC 8032) in pure Python, `cryptography` as an **optional** cross-check | verifiable in 20 lines, public key in the repository, private key on the VM |
| **Storage** | a JSONL file for the journal, nothing else | no managed database to pay for; the journal *is* the artefact |
| **Execution** | Docker on the VM, plus **Microsandbox** (`msb`) as the second backend | "Sandbox boundary: containers or throwaway instances on Vultr, never inside the app process" |
| **Tests** | `pytest` (the only development dependency) | the suite is the specification of the invariants |
| **Deployment** | `cloud-init` + a systemd unit + a verification script | the VM is built from zero by one paste; the verifier proves it answers |
| **Tooling** | `pip` + venv | one command, reproducible |

**On GPUs:** "Vultr GPUs are not available for this event" → **no GPU anywhere in the architecture.**

**On cost:** the 200 $ of credits cover a small VM for the length of the event; the project
itself costs **0 $** — MIT, self-hosted, no service to subscribe to.

---

## 6. The containment moment, step by step (what the video shows)

This is the challenge's explicit requirement — *"One containment moment shown in the video"* —
and the strongest thing in the project. It is **not a promise**: it is one command.

```bash
bash scripts/demo-containment.sh     # no account, no key, no network, nothing installed
```

| Step | What the agent asks for | What the control plane answers | On the screen |
|---|---|---|---|
| 1 | a legitimate order (`exec` inside the sandbox) | `allow.sandbox_exec` → `auto_execute` | `verdict : auto_execute` |
| 2 | `rm -rf /srv/data` (destruction) | `risk.destructive` → **waits for a human** | `verdict : queued_for_human` |
| 3 | the same order, **refused by the operator** | a **signed refusal**, written to the journal | `verdict : rejected` |
| 4 | `read host:///etc/app/secret` (escape) | `forbidden.escape` → **refused, and no human can unlock it** | `HTTP 403` — `verdict : refused` |
| 5 | the journal | verified live, then verified **offline** with no secret | `OK — the chain is intact (N entries checked)` |

> The point that the video must make, in one sentence: **the blast radius was one order that
> never ran.** The control plane blocked it *before* the sandbox had to.

---

## 7. The video plan (the detail is in `submission/DEMO-FILMEE.md`)

Recordings of the screen + a neutral voice, no music, embedded subtitles, **4 min 30** (the
rules ask for "less than 5 min"), **MP4 under 300 MB**.

| Time | Content |
|---|---|
| 0:00–0:25 | the problem: an agent that does real work can break real things — the "blast radius" |
| 0:25–0:50 | the architecture in 25 s: control plane on a Vultr VM, signed orders, quotas, sandbox, replayable journal |
| 0:50–1:40 | *(conditional)* the real work: the agent reads, writes, tests, fixes — each step a signed order |
| 1:40–3:20 | ⭐ **THE CONTAINMENT MOMENT**: the injection, the destructive order, the human refusal, the forbidden order, the isolation running |
| 3:20–4:30 | the replay, the offline verification, the test counter, the closing sentence |

> If time is short, the conditional scene is cut — **never** the containment moment.

---

## 8. 🔒 The red line: what is **not** in the public repository

| Forbidden | The rule that is applied |
|---|---|
| **No key, no secret** | the keys are read from the **environment** of the VM (`/etc/signedorders/env`, mode 0600, outside the repository). The repository contains only variable **names**. |
| **No private data** | the demo target is **our own** service; the "secret" the injection tries to exfiltrate is a **fake** (`DEMO_ONLY_NOT_A_REAL_KEY`). No real data, no customer log, no personal name. |
| **No address of our servers** | no IP, no internal domain, no account identifier in the repository nor in the video (the shot list includes a "scrub" checklist). |
| **The private signing key** | generated **on the VM** at first start (`signedorders init-key`), never pushed. Only the **public** key is versioned. |
| **Third-party code** | the project is written from scratch for this challenge; no line of any internal system is reused — that is the condition of the "original" requirement. |

An automated control (`python3 -m signedorders preflight .`) runs before every commit and fails
on: a secret-shaped pattern, a **complete** private-key block (`BEGIN … PRIVATE KEY` … `END …`),
and internal addresses. It was run on 31/10/2026 and on every commit since: **OK**, and the same
control **refuses** a real key block (counter-proof).

> **On the "written during the window" question.** No published rule of the challenge requires the
> code to be written between 3 and 8 November: the rule book asks for *original* and *MIT-compliant*
> submissions. The project was written from scratch for this challenge and is published under MIT;
> the build window is used to **run** it on the Vultr VM, to record the video and to submit — not to
> write it. Nothing in this repository comes from anywhere else.

---

## 9. The signed order — the central contract

An **order** is what the agent asks for; it is immutable and the control plane never mutates it:

```jsonc
// the order, as the agent sends it (POST /orders)
{
  "task_id": "task_91b2…",              // the mission
  "agent_id": "agent/checkout-fixer",   // who asks
  "action": "exec",                     // read | write | test | exec | apply | restart | delete | network | escalate
  "target": "sandbox://sb-42:/app/main.py",
  "args": { "command": "pytest -q" },
  "justification": "the test suite of the sandbox failed twice"
}
```

The **decision** is what the control plane answers, and it is what gets signed and journalled:

```jsonc
// the decision, as the API returns it
{
  "order": { "id": "so_7f3a…", "created_at": "2026-11-05T14:03:11Z", "…": "the order, canonicalised" },
  "risk": "low",                        // low | medium | high | forbidden — only the policy sets it
  "verdict": "auto_execute",            // the 8 possible verdicts
  "rule_id": "allow.sandbox_exec",      // the rule that decided — traceable
  "reason": "the command runs inside the sandbox",
  "trace": {                            // why that rule won, without a debugger
    "rule_id": "allow.sandbox_exec",
    "explanation": "…",
    "inputs":  { "action": "exec", "target": "sandbox://sb-42:/app/main.py" },
    "outputs": { "risk": "low" },
    "evaluated":  ["forbidden.escape", "forbidden.privilege_escalation", "…"],  // the rules consulted, in order
    "consulted":  ["action", "target"]                                            // the fields actually read
  },
  "quota_snapshot": { "orders": 7, "cpu_seconds": 12, "tokens": 1830 },
  "signature": "ed25519:…",             // over the canonical bytes of the decision
  "public_key": "…",                    // the public key, so the check is possible offline
  "decided_at": "2026-11-05T14:03:12Z"
}
```

**The canonical form is boring on purpose** — that is what makes it reproducible: keys sorted,
no insignificant whitespace, UTF-8 without escaping, `NaN`/`Infinity` rejected. Two runs, two
machines, two Python versions produce the same bytes.

**Guaranteed by the design:**

1. **Verifiable offline** — the public key is in the repository; `signedorders verify` recomputes
   the chain, and `signedorders replay` re-derives the verdict of every recorded decision.
2. **Traceable** — the rule that classified the order, the fields it read and the rules that were
   consulted before it are all in the record.
3. **One hash chain for everything** — `h_n = sha256( h_{n-1} || sha256(canonical_json(record_n)) )`.
   An edited, removed, inserted or reordered record is detected, and the report says which one.
4. **A human approval is an artefact too** — it is signed with the operator's key, and a forged
   approval is refused (the tests cover it).

---

## 10. The risk policy — 14 named rules, in data

`python3 -m signedorders policy` prints them; the order of the table **is** the order of
evaluation, and the forbidden rules come first.

| # | Rule id | Risk | It fires when |
|---|---|---|---|
| 1 | `forbidden.escape` | forbidden | the order tries to reach the host or to walk out of the sandbox root |
| 2 | `forbidden.privilege_escalation` | forbidden | the order asks for a privileged capability (`--privileged`, `sudo`, `chroot`, namespace sharing) |
| 3 | `forbidden.secret_read` | forbidden | the order tries to read a credential (private key, environment file, process environment) |
| 4 | `forbidden.cloud_metadata` | forbidden | the order targets the cloud metadata service |
| 5 | `risk.destructive` | high | the order destroys something (`delete`, `rm`, `truncate`, `DROP`) |
| 6 | `risk.network` | high | the order goes out to the network and the host is not on the allow-list |
| 7 | `risk.mutating_target` | medium | the order mutates the target (apply, restart) → executed, with the quota snapshot |
| 8 | `allow.sandbox_read` | low | a read inside the sandbox |
| 9 | `allow.sandbox_write` | low | a write inside the sandbox |
| 10 | `allow.sandbox_test` | low | running the test suite of the sandbox |
| 11 | `allow.sandbox_exec` | low | running a command inside the sandbox |
| 12 | `allow.escalate` | — | the agent asks a human a free-text question |
| 13 | `allow.network_egress` | low | an egress to a host that **is** on the allow-list |
| 14 | `default.unknown` | high | anything the table does not describe → **high risk, waits for a human** |

**Two kinds of refusal, and the difference matters:**

- **Refusal by policy** — the order is *dangerous by nature* (`forbidden.*`): **no human
  approval can ever unlock it**. The system refuses by construction. This is what makes the
  blast radius zero rather than "small".
- **Refusal by quota** — the order is refused, the journal says so, and when a safety budget
  (CPU seconds, bytes written) is crossed the automatic rollback triggers.

> The unknown is always **high risk**, never low: a rule that nobody wrote must not become a
> rule that nobody checks.

---

## 11. The day-by-day plan (3 → 8 November), with the real status

| Day | Objective | End criterion | Status today (01/11) |
|---|---|---|---|
| **3 nov. (Mon)** | Vultr account + the promo code at the kick-off · create the VM · paste the cloud-init · verify the deployment | the API answers on the public URL | the cloud-init and the verifier are written and tested locally; **the account and the VM are a human action** |
| **4 nov. (Tue)** | the core: policy, quotas, Ed25519, the hash-chained journal, the control plane, the HTTP door | the tests are green; a signed order runs, a forged one is refused | **done and published** — 217 tests |
| **5 nov. (Wed)** | the agent loop (LLM + retry on failure) on Vultr Serverless Inference | one mission passes end to end on a fresh sandbox | the inference client exists (`vultr.py`); the loop is the next piece |
| **6 nov. (Thu)** | the demo target service `checkout-api`, deliberately broken · the web UI | the mission runs and the service comes back alive | planned; the demo does not need it to show containment |
| **7 nov. (Fri)** | clean deployment on Vultr · **public demo URL** · 3 demo takes · the video | the video is ≤ 4 min 30, the PDF deck is ready, the URL is stable | the shot list and the narration are written (`DEMO-FILMEE.md`) |
| **8 nov. (Sun)** | **submission before 20:00 UTC** (2 h of margin) · final re-read of the README · scrub the captures · final commit and push | the form is submitted, the public repository is visible, the video is online | the four documents to paste are written (`submission/SOUMISSION-EN.md`) |

**The submission on 8 November is done by hand by the owner on their lablab.ai account**: the
rules require a registered team, and automating that submission would be unauthorized automation
under the terms of the event.

---

## 12. Business value (scoring criterion 2)

**The problem, as a market:** companies deploy agents that do real work (code, browser, data).
They then discover they are missing three things: a **blast radius** limit, an **audit trail**
(who decided what, and why), and a **human gate** without which the agent cannot be deployed in
production.

**What exists today does not do this work:** *tracing* observes the agent after the fact;
*evals* test it outside production; *guardrails* filter the input. **None of them makes the
*authorisation* a signed, offline-verifiable, replayable operation.** That combination is what
we claim — and it is the technical core of this repository, not a roadmap item.

**Commercial model (hypothesis, *not* implemented in this repository — the challenge entry is
the free, MIT-licensed self-hosted product):**

| Offer | Price | Content |
|---|---|---|
| **Signed Orders OSS** | 0 $ | self-hosted: control plane, executor, journal, replay |
| **Signed Orders Cloud** | 29 $/month/agent · 199 $/month/team | hosting of the signing key, approvals, SIEM, journal retention, managed quotas |
| **Signed Orders Enterprise** | on quote | HSM/KMS, eIDAS-conformant signing, multi-tenant audit |

**Why the jury should believe it (three arguments):**

1. **Avoided cost** — an agent incident costs far more than a monthly subscription; the control
   plane is insurance, not a luxury.
2. **Audit evidence** — the signed journal is a compliance artefact (who, what, when, with which
   signature) — an argument that speaks to security and compliance teams.
3. **Originality** — signing the *authorisation* instead of tracing the *activity* is a simple,
   striking inversion.

---

## 13. Risks and plan B

| Risk | Plan B |
|---|---|
| The Vultr account or the promo code are not ready on 3 nov. | build and test **locally** first — the whole core runs with the standard library, and the containment demo needs neither Docker nor a key |
| `msb` (Microsandbox) does not work on the VM's Ubuntu | the Docker backend **stays** the reference (Vultr recommends it); `msb` becomes a bonus, not a blocker |
| The model is slow or unavailable | deterministic verification (exit code, HTTP status, body fingerprint) as a fallback — which is even **stronger** as a proof; the vision check becomes a bonus |
| The demo breaks during the take | the demo is **recorded in advance**, and the containment script runs identically on a laptop with no account and no key |
| The public repository is judged "too small" | the rules reward quality, not volume; the proof is the replay, the tests and the one-command demo |

---

## 14. What has **not** been done (and why)

- **No account, no key, no spending: 0 $.** The Vultr account, the lablab.ai account, the
  Discord registration and the promo code are human actions, by the owner.
- **No deployment yet.** The cloud-init and the verifier are written and tested locally; the VM
  does not exist, so the *deployment* is the one thing this repository cannot prove by itself.
- **No video.** The shot list, the narration and the "scrub" checklist are written; recording is
  a human action.
- **No submission.** It will be done by hand on 8 November, before 20:00 UTC.
- **The agent loop, the demo target service and the web UI are not in this repository** — see
  section 2.3. Nothing here pretends otherwise.
- **No modification of the JetBrains plugin** of this slot: it is frozen and archived.

---

## 15. The official requirements, and where each one is proved

| Requirement (verbatim from the event pages) | Where it is proved in this repository |
|---|---|
| « GitHub repository with setup and documentation » | this repository: MIT `LICENSE` at the root, `pip install -e ".[dev]"`, `pytest`, the launch command in the README |
| « Vultr VM backend deployment » | `deploy/cloud-init.yml` (builds the VM from zero), `deploy/verifier-deploiement.sh` (proves it answers), `submission/DEPLOIEMENT-VULTR.md` (the exact commands) |
| « Public demo URL » | the four read routes of the HTTP door; the URL is `http://<VM-IP>:8000/health` once the VM exists |
| « Recorded demo video » | `submission/DEMO-FILMEE.md`: the 5 captures, the minute-by-minute plan, the English narration |
| « Clear architecture and use-case explanation » | this document, and the README sections "The idea, in one paragraph", "How it works", "How Vultr powers this" |
| « One containment moment shown in the video » (challenge 01) | section 6 above, and `scripts/demo-containment.sh` — reproducible in one command, with no account and no key |
| « In your public GitHub README, explain the Vultr services and infrastructure used » | the README section "How Vultr powers this" (Cloud Compute, Serverless Inference, the two sandbox backends, the API, the credits) |
| « Submissions must be original and MIT-compliant » | the project is original, written from scratch for this challenge, and MIT-licensed |
| « Teams consist of 1–6 people » | solo submission |

---

*Written for the Vultr: Agent Rush Hackathon (3–8 November 2026). Infrastructure and inference
provided by [Vultr](https://www.vultr.com/); the event is run by [lablab.ai](https://lablab.ai/).*
