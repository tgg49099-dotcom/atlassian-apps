# Support — ML Experiment Cockpit

If you use **ML Experiment Cockpit**, this page explains how to get help and how
to report a problem.

## What the plugin does

**ML Experiment Cockpit** is a free JetBrains IDE plugin that shows your ML
experiments without leaving the IDE:

- **Experiments** — your MLflow tracking server's experiments, runs, parameters
  and metric history, with a line chart per metric.
- **Optuna** — studies and trials read from an Optuna Dashboard.
- **Models** — the MLflow Model Registry: registered models, their versions,
  stage and status.
- **Sweeps** — Weights & Biases hyperparameter sweeps, read-only.

It is **read-only**: it never writes to your MLflow, Optuna or W&B servers, and
it sends nothing anywhere except to the servers you configure (see
[PRIVACY.md](PRIVACY.md)).

## How to get help

1. **Read the FAQ below** — it covers the questions we are asked most.
2. **Email** — `tgg.apps.support@gmail.com`. Please include:
   - your IDE and its version (*Help → About* in the IDE shows the build, e.g. `PY-262.8665.309`);
   - the plugin version (the Plugin Manager shows it);
   - the steps to reproduce;
   - the exact message shown in the tool window;
   - the server and its version (MLflow `>= 2.0`, Optuna Dashboard, W&B).
3. **GitHub issue** — the repository is public
   (`github.com/tgg49099-dotcom/jetbrains-plugins`). Use the label `question`
   for a question, `bug` for a defect, `enhancement` for an idea. **Do not paste
   credentials or confidential experiment data in a public issue.** For a
   security problem, do not open an issue: see [SECURITY.md](SECURITY.md).

## FAQ

### 1. The Experiments tab is empty — where do I start?

Open *Settings → Tools → ML Experiment Cockpit* and set the **MLflow tracking
URI** (for example `http://localhost:5000`), then press **Refresh** in the
tool window. The URI is a server you run; the plugin does not embed a hosted
service.

### 2. "Model Registry is shipped with MLflow 2.0+ only" — what does it mean?

This message can hide a different cause. The most common one is a **405** from
the server: MLflow 3.x only accepts `GET` on `runs/get`,
`registered-models/search`, `registered-models/get` and
`model-versions/search`. The plugin uses the method your server accepts. If
the error persists, send us the exact message and your MLflow version — see
the "how to get help" section.

### 3. How much data does it read?

Up to 500 experiments, 100 runs per experiment, 500 registered models, 500
model versions, and 5 000 metric points per run. W&B sweeps are read without
pagination (the first 50 sweeps). These are the server's own answer limits —
the plugin shows what the server returns, it does not truncate silently.

### 4. Is my data sent to JetBrains or to the plugin author?

No. See [PRIVACY.md](PRIVACY.md): the plugin only talks to the servers you
configure, and stores its settings locally (the W&B API key in the IDE's
PasswordSafe).

### 5. Which IDEs are supported?

IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion and other IntelliJ-platform
IDEs, from build **243** (2024.3) to build **263** (2026.3). The plugin is
free and works with the Community editions.

### 6. Can I use it with a private or self-hosted W&B?

Yes: the plugin posts to `<base>/graphql` on the base URL you configure, so a
self-hosted W&B works the same way as the cloud one.

### 7. How do I report a security problem?

Not on a public issue — see [SECURITY.md](SECURITY.md).
