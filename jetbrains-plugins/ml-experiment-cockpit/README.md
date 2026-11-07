# ML Experiment Cockpit

JetBrains IDE plugin — connect to an MLflow Tracking Server, see your ML experiments without leaving the IDE.

> **Status: v0.0.22 (local build only — not yet published).**
> Slot 5 (JetBrains Marketplace) business mission, second plugin.
> Niche: ML engineer tooling for PyCharm Professional + DataSpell. See `/work/CYCLE4_NICHE_TOP.md` for the market analysis.

## What this plugin does (v0.0.22)

1. **Configure the five fields** in `Settings → Tools → ML Experiment Cockpit`:
   - MLflow: e.g. `http://localhost:5000`.
   - Optuna: e.g. `http://localhost:8080`.
   - **W&B API key** (Bearer, free at https://www.wandb.ai/settings → Danger Zone → API keys) — NEW in v0.0.7, **stored in `PasswordSafe` since v0.0.9**.
   - **W&B entity** (default pre-fill for the Sweeps tab) — NEW in v0.0.7.
   - **W&B project** (default pre-fill for the Sweeps tab) — NEW in v0.0.7.
2. **Open the Tool Window** via `View → Tool Windows → ML Experiment Cockpit`.
3. **Tab "Experiments" (MLflow, v0.0.1–v0.0.3)** — see below.
4. **Tab "Optuna" (v0.0.4)**: click "Refresh" to fetch studies from the
   Optuna Dashboard, click a study in the left tree to populate the right
   panel with: summary (name, direction, n_trials, n_complete, best value,
   datetime_start), top-5 trials table sorted by value, and a horizontal
   bar chart of the hyperparameter that takes the most distinct values.
5. **Tab "Models" (v0.0.6)**: click "Refresh" to list registered
   models from your MLflow Model Registry (no new settings — uses the
   same tracking URI). Expand a model to see its latest versions with
   stage (Production / Staging / Archived / None), status (READY /
   PENDING_REGISTRATION / FAILED_REGISTRATION), and run_id. Click a model
   to view the description, creation timestamp, and a per-version summary.
   Click a version to view its details.
6. **Tab "Sweeps" (v0.0.7)**: type an entity + project, click
   "Refresh" to list W&B Hyperparameter Sweeps via the GraphQL API at
   `POST https://api.wandb.ai/graphql`. The tree is grouped by sweep
   state (running / finished / failed / crashed / pending / stopped),
   coloured per-state (blue/green/red). Click a sweep to see name, id,
   state, runs total, created + updated timestamps (the W&B API does not
   expose a best run for a sweep).

### MLflow (Experiments tab, unchanged from v0.0.3)

7. Click "Refresh" — the plugin fetches `POST /api/2.0/mlflow/experiments/search`
   and builds a 2-level tree: Experiment → Run.
8. Click any run — the right panel loads its params + latest metric values
   via `POST /api/2.0/mlflow/runs/get`.
9. Pick a metric from the combo box — fetches the metric's full history via
   `GET /api/2.0/mlflow/metrics/get-history` and renders it as a line chart
   (auto-scaled Y axis, hand-drawn with `Graphics2D`).

### Compare (Pro tier n°1, v0.0.22)

10. Tab **"Compare"** — the first paid feature, sold by JetBrains as a
    subscription under the product code `PPCOCKPITPRO`. Right-click a run in
    the Experiments tree → **« Compare with… »**: the two runs are shown side
    by side (name, `run_id`, status, start, duration), the **parameters** in
    one table, the **metrics** in another (last value of each side, absolute
    gap, relative gap), and the two metric curves are drawn **on the same
    axis** (A in blue, B in orange). **« Copy (Markdown) »** puts both tables
    in the clipboard.
    Without a licence the tab stays **visible but locked**, with a
    **« Get Pro »** button that opens JetBrains' own purchase dialog. The
    four free tabs are **strictly unchanged** (same limits: 500 experiments,
    100 runs per experiment, 500 registered models, 500 model versions,
    5 000 metric points per run) — `GratuitInchangeTest` proves it.

That is the entire v0.0.22 surface. The build pipeline (`buildPlugin` + `verifyPlugin`)
and the upload script (`scripts/upload_to_marketplace.sh`) are validated so the
owner can publish as soon as `JETBRAINS_MARKETPLACE_TOKEN` arrives.

## Roadmap (post-MVP)

| Version | Scope | Status |
|---|---|---|
| 0.0.1 | Settings + Connect-to-MLflow balloon action | ✅ done (cycle 5) |
| 0.0.2 | Tool Window: Experiment → Run tree + per-run params/metrics panel | ✅ done (cycle 6) |
| 0.0.3 | Per-metric time-series chart (line chart) below the run detail | ✅ done (cycle 7) |
| 0.0.4 | Optuna study visualization (summary + top-5 trials + bar chart) | ✅ done (cycle 8) |
| 0.0.5 | Replicate deploy hooks (Tool Window tab + menu action) | ❌ withdrawn in v0.0.8 |
| 0.0.6 | MLflow Model Registry browser (Tool Window tab) | ✅ done (cycle 10) |
| 0.0.7 | W&B sweeps browser (Tool Window tab) | ✅ done (cycle 11) |
| 0.0.8 | Jury review fixes: drop the v0.0.5 Replicate action, add `depends=platform`, extend untilBuild to 262.*, disable Settings Sync | ✅ done (cycle 15) |
| 0.0.9 | W&B API key migrated to `PasswordSafe`; verification matrix now covers PyCharm 2025.2 / 2026.1 / 2026.2 | ✅ done (cycle 18) |
| 0.0.10 | Vendor info patched to the real owner profile (`Tgg Labs`, `tgg.apps.support@gmail.com`, `vendor/tgg-labs`) | ✅ done (cycle 48) |
| 0.0.11 | First-public-release change-notes (never published; superseded by 0.0.12) | ✅ built, never shipped |
| 0.0.12 | Public plugin id `com.slot5.mlexp` → `com.tgglabs.mlexp`; `META-INF/pluginIcon.svg` + `pluginIcon_dark.svg` shipped; change-notes collapsed to the single real first release | ✅ built, ready to publish |
| 0.0.22 | **Pro tier n°1 — “Compare 2 runs”**: 5th tab (Compare), parameters + metrics side by side, superposed curves, Markdown export, native JetBrains licence gate (`PPCOCKPITPRO`). The four free tabs are strictly unchanged. | ✅ built (cycle 149), **not published** |

Each version adds a thin slice of the niche. See `/work/CYCLE4_NICHE_TOP.md` for the full plan.

## Build

Same toolchain as the sibling `/work/hello-world-plugin` project:

```bash
export JAVA_HOME=/work/tools/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /work/ml-experiment-plugin
./gradlew buildPlugin verifyPlugin
./scripts/prevol.sh          # pre-flight, on the BUILT jar — never publish without it
```

Output ZIP (ready to publish, but **do not push yet**):

```
build/distributions/ml-experiment-cockpit-0.0.22.zip
```

`./gradlew clean` deletes the whole `build/` tree, including any earlier ZIP kept as a
fallback. `buildPlugin` without `clean` leaves the previous ZIPs in place.

## Pre-flight

`scripts/prevol.sh` enforces the publication rules on the artefact that ships, not on
the sources: it extracts the plugin JAR out of the distribution ZIP and scans the
`META-INF/plugin.xml` inside it for placeholder / withdrawn-feature residue, checks the
id / version / vendor / `until-build`, checks that both icons are inside the JAR, and
checks that the change-notes start with the shipped version (2026.1.0) and list at
least one bullet (the older published versions may follow as history). Exit 0 = green,
non-zero = do not publish.

## Upload to JetBrains Marketplace

Blocked until the vendor publishes the first plugin manually (UI required for the first publication).
Once `JETBRAINS_MARKETPLACE_TOKEN` is set:

```bash
export JETBRAINS_MARKETPLACE_TOKEN="perm:..."
./scripts/upload_to_marketplace.sh \
    ./build/distributions/ml-experiment-cockpit-0.0.22.zip
```

The script auto-extracts `xmlId=com.tgglabs.mlexp` from `plugin.xml` inside the ZIP.

## Repository layout

```
ml-experiment-plugin/
├── build.gradle.kts                            IntelliJ Platform Gradle Plugin 2.7.2 (Kotlin DSL)
├── gradle.properties                           Plugin id, version, target IDE, vendor
├── settings.gradle
├── gradle/wrapper/                             Gradle 8.10.2 wrapper
├── scripts/
│   ├── upload_to_marketplace.sh                Multipart POST to /api/updates/upload
│   └── prevol.sh                               Pre-flight on the BUILT jar (run before any publish)
├── src/main/
│   ├── kotlin/com/slot5/mlexp/
│   │   ├── MlflowSettings.kt                   PersistentStateComponent (URIs v0.0.4 + W&B v0.0.7; W&B key in PasswordSafe v0.0.9; deploy fields removed v0.0.8)
│   │   ├── MlflowSettingsConfigurable.kt       Settings UI panel (5 fields v0.0.8)
│   │   ├── ConnectToMlflowAction.kt            "Connect to MLflow" balloon action (v0.0.1)
│   │   ├── MlflowClient.kt                     Shared MLflow REST client (v0.0.3: + getMetricHistory; v0.0.6: + model registry; v0.0.22: + getRunDetails, + paginated history for Pro)
│   │   ├── MlflowToolWindowFactory.kt          ToolWindowFactory (v0.0.8: 4 tabs — Deploy removed; v0.0.22: + Compare tab)
│   │   ├── MlflowToolWindowPanel.kt            Tool Window Swing panel (v0.0.3: + chart slot; v0.0.8: -LAST_RUN_ID; v0.0.22: + “Compare with…” menu)
│   │   ├── MetricsChartPanel.kt                Time-series chart panel (v0.0.3)
│   │   ├── OptunaClient.kt                     Optuna Dashboard REST client (v0.0.4)
│   │   ├── OptunaToolWindowPanel.kt            Optuna Tool Window Swing panel (v0.0.4)
│   │   ├── ModelRegistryToolWindowPanel.kt     Models Tool Window Swing panel (v0.0.6)
│   │   ├── WandbClient.kt                      W&B Sweeps GraphQL client (v0.0.7)
│   │   ├── SweepToolWindowPanel.kt             Sweeps Tool Window Swing panel (v0.0.7)
│   │   └── pro/                                **Pro tier n°1 (v0.0.22)**
│   │       ├── ProGate.kt                      Pure-Kotlin licence gate (states PRO/ESSAI/EXPIRE/ABSENT/INCONNU)
│   │       ├── LicenceNatif.kt                 The ONLY file touching `com.intellij.ui.LicensingFacade`
│   │       ├── ComparaisonRuns.kt              Pure comparison core (params, metrics, gaps, Markdown)
│   │       ├── CourbeDiff.kt                   Superposed 2-run chart (A blue, B orange)
│   │       ├── ComparaisonHub.kt               Relay between the free tree and the Compare tab
│   │       └── PanneauComparaison.kt           The “Compare” tab UI (locked without a licence)
│   └── resources/META-INF/
│       ├── plugin.xml                          Actions + services + notificationGroup + toolWindow
│       ├── pluginIcon.svg                      40×40 light-theme listing icon
│       └── pluginIcon_dark.svg                 40×40 dark-theme listing icon
├── CHANGELOG.md
├── LICENSE                                     Apache 2.0
└── README.md
```