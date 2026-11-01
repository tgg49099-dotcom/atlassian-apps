# SOUMISSION — Vultr: Agent Rush Hackathon — challenge 01 « Blast Radius Zero »

> **Ce que Tgg colle dans lablab.ai.** Tout le fichier ci-dessous est en anglais.
> Les deux lignes marquées **[À REMPLIR]** sont les seules qui attendent une action humaine.
> **Date limite de soumission : 8 novembre 2026, 20:00 UTC.**
>
> *Astuce : ce document contient aussi les « requirement lines » (une ligne par exigence
> officielle du jury) — elles sont à la fin, section 6. Si lablab.ai demande des champs
> séparés, cette section sert de réponse champ par champ.*

---

## 1. Project title

**Signed Orders — a containment-first control plane for autonomous agents**

## 2. Short summary (one paragraph)

Signed Orders turns every privileged action of an autonomous agent into a written, signed,
verifiable order. Low-risk orders run on their own; high-risk ones wait for a human; dangerous
ones are never approvable at all. Every action runs in a throw-away sandbox under quotas, and
every decision is written in a hash-chained journal that anyone can verify offline, with no
secret and no server.

> *(Ultra-short version, 255 characters, if the form asks for a tagline:)*
> `Signed Orders: every action of an AI agent becomes a signed, verifiable order. Sandboxed, quota'd, hash-chained journal. Vultr VM + Serverless Inference.`

## 3. Long description (two paragraphs)

**The problem, and the idea.** An agent that only chats is a demo; an agent that executes
safely is a product. The blast radius of an autonomous agent is decided long before it acts —
by whatever decides what it is allowed to do. Signed Orders moves that decision out of the
model and into a control plane. The agent has no shell, no secret and no write access: its
only privilege is to *request* an order. The control plane classifies the request with a table
of named, ordered, auditable rules (no model in the decision loop), enforces per-agent quotas
and per-task budgets, signs the order with Ed25519, and only then runs it — in a fresh
sandbox that is destroyed right after. Low-risk orders (read a log, run the tests inside the
sandbox) go through automatically; high-risk ones (restart a service, delete something) wait
for a *signed human approval* that expires if nobody answers — silence is not an approval;
and orders that are dangerous by nature (reading a secret, escaping the sandbox, reaching the
cloud metadata endpoint) are refused outright, with no override. A prompt injection can make
the agent ask for anything; it can still only execute what has been signed.

**The evidence, and the Vultr stack.** Every decision and every execution is appended to a
hash-chained journal, `h_n = sha256(h_{n-1} || sha256(canonical_json(record_n)))`, verifiable
offline with the standard library only — no key, no server, no account: anyone can clone the
repository and check that the story we tell about the agent was not edited afterwards. The
isolation is published as data: the exact `docker run` line (`--network=none`, 256 MB, 1 CPU,
128 PIDs, read-only root filesystem, all capabilities dropped, `no-new-privileges`, uid
65534), plus a second backend on microVMs (Microsandbox / libkrun), with a test that keeps
the manifest and the code in sync. The backend runs on a **Vultr Cloud Compute** VM
(Ubuntu 24.04, one VM, one public URL); the only model calls go through **Vultr Serverless
Inference**, whose key never leaves the control plane and never enters a sandbox. The
repository ships with its own proof: **218 tests — 217 passed, 1 skipped** on a bare clone
(the 218th cross-checks the pure-Python Ed25519 against the optional `cryptography`
library: `pip install -e ".[reference]"` gives 218 passed), a cloud-init that builds the VM
from scratch, a deployment verifier, and a **single command** —
`bash scripts/demo-containment.sh` — that reproduces the containment moment end to end,
with **no account, no key and nothing to install** (it only needs Python 3).

## 4. Architecture

```
   AGENT (in the sandbox, no shell, no secret)      CONTROL PLANE (on the Vultr VM)        EXECUTOR
   ─────────────────────────────────────────      ────────────────────────────────        ──────────
   may: POST /orders ─────────────────────────▶   1. identify the agent
   may NOT:                                        2. classify the risk (named rules)
    • run a shell                                  3. enforce the quotas (sliding window)
    • read a secret                                4. sign (Ed25519) — or queue for a human
    • write outside the sandbox                    5. run in a fresh sandbox ──────────▶   6. append to the
                                                     (the agent never gets a shell)        journal
                                                                                           7. destroy the
                                                                                              sandbox
```

**The three properties that fall out of this separation**

1. **The agent has no shell.** It can *ask* for anything; it can only *run* what was signed.
2. **The private key never leaves the VM** and is not in the repository — the **public** key
   is, so anyone can verify the journal offline with no secret at all.
3. **Some things are never approvable.** `forbidden.*` orders are refused with no human
   override; they are a different kind of refusal from a high-risk order waiting in the queue.

**Vultr services used:** Vultr Cloud Compute (the Ubuntu 24.04 VM that hosts the control
plane, the journal, the key and the sandboxes), Vultr Serverless Inference (the only place a
model is called — the agent loop), and the Vultr sandboxing backends (Docker containers and
Microsandbox microVMs, one fresh sandbox per task, destroyed after). No GPU is used — none is
available for this event.

## 5. Links

| | |
|---|---|
| **Repository (public)** | <https://github.com/tgg49099-dotcom/atlassian-apps/tree/main/agent-rush/signed-orders> |
| **Repository — the project alone, at the root of a branch** | <https://github.com/tgg49099-dotcom/atlassian-apps/tree/signed-orders> |
| **Slide deck (PDF, 7 pages)** | <https://github.com/tgg49099-dotcom/atlassian-apps/blob/signed-orders/slides/signed-orders.pdf> |
| **Public demo URL** | **[À REMPLIR — Tgg]** `http://<VM-IP>:8000/health` |
| **Demo video** | **[À REMPLIR — Tgg]** (the shot list is in `submission/DEMO-FILMEE.md`) |
| **License** | MIT, at the root of the project |
| **Team** | 1 person (solo submission is allowed: « Teams consist of 1–6 people ») |
| **The proof, in one command** | `bash scripts/demo-containment.sh` — the containment moment, no account, no key, no install |

## 6. One line per official requirement of the jury

| # | Requirement (verbatim) | Proof / what is left | Who | Deadline |
|---|---|---|---|---|
| 1 | « GitHub repository with setup and documentation » | Public repository, MIT `LICENSE` at the root of the project, `pip install -e ".[dev]"`, `pytest` (217 passed, 1 skipped), the launch command and the architecture in the README. Verified without any account: every file returns HTTP 200. | **done** | — |
| 2 | « Vultr VM backend deployment » | `submission/DEPLOIEMENT-VULTR.md` (the exact procedure, web console or CLI, **plus a complete manual fallback**) + `deploy/cloud-init.yml` (installs everything, generates the keys **on the VM**, starts the systemd service) + `deploy/signedorders.service` (the same unit, as a file) + `deploy/verifier-deploiement.sh` (checks the deployment answers, 6 PASS). The commands are written and tested locally; **the VM itself is not created yet** — creating the Vultr account and paying is a human action. | **Tgg** creates the account and the VM | before 8/11 20:00 UTC |
| 3 | « Public demo URL » | The control plane answers 5 read routes — `/health`, `/policy`, `/quotas`, `/orders`, `/journal/verify` — and 2 write routes. The URL is `http://<VM-IP>:8000/health`. **No URL yet**: it needs the VM of line 2. | **Tgg** | before 8/11 20:00 UTC |
| 4 | « Recorded demo video » | The minute-by-minute script, the 5 captures and the English narration are in `submission/DEMO-FILMEE.md`. The containment moment is reproducible on any machine with `bash scripts/demo-containment.sh`. **Not recorded yet** — recording is a human action. | **Tgg** | before 8/11 20:00 UTC |
| 5 | « Clear architecture and use-case explanation » | `DESIGN.md` (data flow, policy, signing, journal, deployment) and the README sections « The idea, in one paragraph », « How it works », « How Vultr powers this ». | **done** | — |
| 6 | « One containment moment shown in the video » (challenge 01) | Scene 4 of the video script (1:40 → 3:20). It is a **real, reproducible** moment: the script prints the order `rm -rf /srv/data` → `queued_for_human` → **refused by the human**, the order `host:///etc/app/secret` → **refused (HTTP 403), no override possible**, and the journal chain verified offline. The same transcript is in the repository, produced by `bash scripts/demo-containment.sh` — one command, no account, no key, no install. | **script done**, the video is **Tgg's** | before 8/11 20:00 UTC |
| 7 | « In your public GitHub README, explain the Vultr services and infrastructure used » | The README section « How Vultr powers this » (one table: Cloud Compute, Serverless Inference, the two sandbox backends, the API, the credits). | **done** | — |
| 8 | « Submit on lablab.ai » + the Discord of the event | The registration and the submission are human actions (account + payment). **Everything needed to paste is in this file.** | **Tgg** | before 8/11 20:00 UTC |
| 9 | « Submissions must be original and MIT-compliant » | The project is original, written for this event, and MIT-licensed (`LICENSE` at the root of the project). | **done** | — |
| 10 | « Teams consist of 1–6 people » | Solo submission (1 person). | **done** | — |

## 7. The three sentences to say if the jury asks

1. **What is it?** A control plane that makes every privileged action of an AI agent a signed,
   verifiable order, executed in a throw-away sandbox under quotas, with a hash-chained
   journal that anyone can check offline.
2. **Why Vultr?** One Ubuntu 24.04 Cloud Compute VM hosts the control plane, the key and the
   sandboxes; Vultr Serverless Inference is the only place a model is called; the sandboxes are
   Docker containers and Microsandbox microVMs, one per task, destroyed after. No GPU is needed.
3. **Why is it different?** The model never decides what it is allowed to do: a table of
   named, ordered rules does, and the decision is signed and written down. The blast radius of
   the agent is the size of the queue a human has not answered yet — and never the host.

---

## 8. Tags (to paste in the form)

```
agent, ai-agents, autonomous-agents, sandbox, security, devops, vultr,
serverless-inference, ed25519, audit, audit-trail, python, open-source, mit
```

---

### Notes for Tgg (not to be pasted into lablab.ai)

- The repository is a **multi-project container**: the project is in `agent-rush/signed-orders/`.
  The branch `signed-orders` shows it alone at the root of the repository. Both links are in
  section 5; give the jury the first one, and mention the second in the README section
  « Hackathon submission ».
- The `LICENSE` (MIT) is at the root of the **project** (`agent-rush/signed-orders/LICENSE`).
  It is **not** at the root of the container repository, because that repository also hosts an
  Apache-2.0 JetBrains plugin — putting an MIT at its root would be false. If the owner
  creates a dedicated repository `tgg49099-dotcom/signed-orders`, the licence becomes visible
  in the « About » section and the poller pushes the project there automatically.
- The submission is **free** and no prize is paid before the judging is over
  (« Prize distribution may take up to 90 days »), so the declared revenue stays **0 $** until
  the result is known.
