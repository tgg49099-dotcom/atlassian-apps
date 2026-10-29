# Privacy Policy — ML Experiment Cockpit

**Last updated: 2026-10-26.**

This Privacy Policy describes what **ML Experiment Cockpit** (the "Plugin"), a
JetBrains IDE plugin published by **Tgg Labs** on the JetBrains Marketplace, reads,
stores and sends when you install and use it.

The short version: **the Plugin talks only to the servers you configure.** It
contains no telemetry, no analytics, no advertising, and no code that sends
anything to JetBrains or to us. Everything stays on your machine and on the
servers you already run.

## 1. Data the Plugin reads

The Plugin is a read-only viewer. It queries the MLflow / Optuna / W&B servers
you point it at and displays what those servers return:

| Server | What is requested | Typical contents |
|---|---|---|
| **MLflow Tracking Server** (your URI, e.g. `http://localhost:5000`) | `POST /api/2.0/mlflow/experiments/search`, `POST /api/2.0/mlflow/runs/get`, `GET /api/2.0/mlflow/metrics/get-history`, `POST /api/2.0/mlflow/registered-models/search`, `POST /api/2.0/mlflow/registered-models/get`, `POST /api/2.0/mlflow/model-versions/search` | experiment names, run IDs, parameters, metric names and values, timestamps, model names and versions |
| **Optuna Dashboard** (your URI, e.g. `http://localhost:8080`) | the dashboard's REST endpoints for studies, trials and their parameters | study names, trial numbers, parameter values, objective values |
| **Weights & Biases** (`https://api.wandb.ai/graphql`, only if you enter a W&B API key, entity and project) | `POST /graphql` with the `listSweeps` and sweep-detail queries | sweep names, sweep IDs, run summaries, metric values, timestamps |

The Plugin **never** writes to these servers: it issues no create, update,
delete or train operation.

## 2. Data the Plugin stores, and where

| Item | Where it is stored | Synced to the cloud? |
|---|---|---|
| MLflow tracking URI, Optuna dashboard URI, W&B entity, W&B project | your IDE's configuration directory, in `mlExperimentCockpit.xml` | **No.** The settings component is registered with `roamingType = DISABLED`, so these values are kept on the machine and are not synchronized through IDE Settings Sync. |
| W&B API key | the IDE's own credential store (**PasswordSafe**), under the credential pair *ML Experiment Cockpit / W&B API key* | **No.** It is not written in the settings XML. Versions 0.0.7–0.0.8 stored the key in plain text in the settings file; it is migrated into PasswordSafe on first load after upgrading. |

No telemetry, no usage statistics, no crash-reporting service, no identifier of
your machine, no advertising ID. The Plugin contains no code that sends data to
JetBrains, to the Marketplace, or to us.

## 3. What leaves your machine

Exactly the requests listed in section 1, to the hosts you configured:

* the MLflow tracking server and the Optuna dashboard **you** set (usually on
  `localhost` or on your own infrastructure);
* `api.wandb.ai`, only if you explicitly configure a W&B API key, entity and
  project — in that case the request carries your key as a bearer token, exactly
  as the W&B API expects.

We do not operate any server, do not proxy your requests, and never see the
contents of your experiments.

## 4. What JetBrains sees

JetBrains Marketplace is operated by JetBrains, not by us. When you install the
Plugin from the Marketplace, JetBrains records the download against the listing
and shows the listing's public information (name, version, description, tags,
and the links published by the vendor). If you post a comment or a rating, that
comment is public on the listing. Those are JetBrains' own data flows, governed
by JetBrains' privacy policy.

We (Tgg Labs) receive nothing from the Marketplace about who installs the
Plugin. The only contact channel we offer is the support mailbox below.

## 5. Uninstalling

Uninstalling the Plugin removes its settings from the IDE. The MLflow / Optuna
/ W&B data stays on your servers — the Plugin never copied any of it.

## 6. Children

The Plugin is a developer tool for adults; it is not directed at children.

## 7. Changes to this policy

If this policy changes, the updated version is published in this repository and
the "Last updated" date above changes. Continuing to use the Plugin after a
change means the new policy applies to it.

## 8. Contact

Questions about this policy: **tgg.apps.support@gmail.com**
(see [SUPPORT.md](SUPPORT.md) for the full support channel).
