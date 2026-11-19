# ML Experiment Cockpit

JetBrains IDE plugin — connect to an MLflow Tracking Server, see your ML experiments without leaving the IDE.

> **Status: published on the JetBrains Marketplace.** The sources in this repository are version **2026.1.7**. The *Versions* tab of the plugin page lists the versions that are actually published, with their change notes. Plugin page: https://plugins.jetbrains.com/plugin/35416-ml-experiment-cockpit

## Install

The plugin is published on the JetBrains Marketplace (listing **35416**).

1. In your IDE, open `Settings/Preferences > Plugins > Marketplace`.
2. Search for **ML Experiment Cockpit** and click **Install**.
3. Restart the IDE if it asks for it, then open the tool window with
   `View > Tool Windows > ML Experiment Cockpit`.

Compatibility: the published build declares `since-build 243.0` and `until-build 263.*`
(IntelliJ-based IDEs from 2022.3 up to 2026.3).

- Marketplace listing (plugin 35416): https://plugins.jetbrains.com/plugin/35416-ml-experiment-cockpit
- Product README (full documentation): https://github.com/tgg49099-dotcom/atlassian-apps/blob/main/jetbrains-plugins/ml-experiment-cockpit/README.md

## What this plugin does (sources here = 2026.1.7)

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

### Compare (Pro tier n°1; sources here = 2026.1.7)

10. Tab **"Compare"** — the first paid feature, sold by JetBrains as a
    subscription under the product code `PPCOCKPITPRO`. Right-click a run in
    the Experiments tree → **« Compare with… »**: the two runs are shown side
    by side (name, `run_id`, status, start, duration), the **parameters** in
    one table, the **metrics** in another (last value of each side, absolute
    gap, relative gap), and the two metric curves are drawn **on the same
    axis** (A in blue, B in orange). **« Copy (Markdown) »** puts both tables
    in the clipboard.
    Without a licence the tab stays **visible but locked**, with a
    **« Get Pro »** button that opens JetBrains' own purchase dialog.
    **2026.1.2:** the licence state is read again when the purchase dialog
    closes, so the tab opens as soon as the licence is active instead of
    staying locked until the IDE restarts.
    The four free tabs are **strictly unchanged** (same limits: 500 experiments,
    100 runs per experiment, 500 registered models, 500 model versions,
    5 000 metric points per run) — `GratuitInchangeTest` proves it.
> The **Compare** tab requires a licence sold by the JetBrains Marketplace — see the plugin page for availability: https://plugins.jetbrains.com/plugin/35416-ml-experiment-cockpit

That is the entire surface of the plugin (sources here = 2026.1.7). The build pipeline
(`buildPlugin` + `verifyPlugin`) is validated, and the upload procedure is documented
below.

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
| 0.0.12 | Public plugin id `com.slot5.mlexp` → `com.tgglabs.mlexp`; `META-INF/pluginIcon.svg` + `pluginIcon_dark.svg` shipped; change-notes collapsed to the single real first release | ✅ published on the Marketplace |
| 0.0.13 - 0.0.21 | Intermediate releases published on the Marketplace (version list on the plugin page) | ✅ published |
| 0.0.22 | **Pro tier n°1 — “Compare 2 runs”**: 5th tab (Compare), parameters + metrics side by side, superposed curves, Markdown export, native JetBrains licence gate (`PPCOCKPITPRO`). The four free tabs are strictly unchanged. | ✅ built - in JetBrains review (submitted 10/11/2026), not yet live |
| 2026.1.0 | The Pro tier is put on sale on the Marketplace (product code `PPCOCKPITPRO`, 29 EUR/year, VAT depending on your country, 30-day free trial). | ✅ published on the Marketplace |
| 2026.1.2 | **The Compare tab unlocks as soon as the licence becomes active**: the licence state is read again when the purchase dialog closes (new `AcheteurPro` seam + `ComparaisonDeverrouilleTest`). The version number now starts with `2026.1`, like the `release-version="20261"` of the product descriptor, as the official "add-required-parameters" rule requires. | ✅ built - superseded by 2026.1.4, never published on its own |
| 2026.1.3 | The purchase dialog is opened with the current platform APIs: the plugin reports no deprecated, removed or override-only API usage on recent IDE versions. | ✅ built - superseded by 2026.1.4, never published on its own |
| 2026.1.4 | **The licence check verifies the JetBrains signature** (official sample): the Pro tier is unlocked only by a licence stamp whose signature is verified against the two public JetBrains root certificates; a hand-written stamp no longer opens the paid tab. | ✅ published on the Marketplace |
| 2026.1.5 | **A mistyped server address now gives a clear message**: the MLflow, Optuna and W&B tabs report the address and the missing scheme (one message for the three servers) instead of "Unexpected error" and a red error balloon; the W&B Sweeps tab no longer fails with a raw Java error. No visible change when the address is correct. | ✅ built - frozen, awaiting upload (md5 `49e0e4ae…`) |
| 2026.1.6 | **The Pro tier price is now written in euros**: every text that names the price (the description table, the locked Compare tab, these notes) reads `29 EUR/year, VAT depending on your country`; the Compare tab says `Pro: active` without distinguishing a free trial from a paid licence. No functional change: the price stays 29 EUR/year and the trial stays 30 days. | ✅ published on the Marketplace |
| 2026.1.7 | **The locked Compare tab now sells the Pro tier**: the locked card says *Compare is a Pro feature*, states the price (29 EUR/year, VAT depending on your country) and the 30-day free trial, and links to the plugin page on the JetBrains Marketplace, where the buy and trial buttons are. No payment is taken in the plugin. The four free tabs are unchanged. | ✅ built - the version in these sources (see the *Versions* tab of the plugin page for what is published) |

Each version adds a thin slice of the niche.

## Build

Build with JDK 21 and the Gradle wrapper:

```bash
export JAVA_HOME=<your JDK 21>
export PATH="$JAVA_HOME/bin:$PATH"
cd ml-experiment-plugin
./gradlew buildPlugin verifyPlugin
```

Output ZIP (the version in these sources is 2026.1.7):

```
build/distributions/ml-experiment-cockpit-2026.1.7.zip
```

`./gradlew clean` deletes the whole `build/` tree, including any earlier ZIP kept as a
fallback. `buildPlugin` without `clean` leaves the previous ZIPs in place.

## Pre-flight

Before any upload, check the artefact that ships, not the sources: extract the plugin JAR
out of the distribution ZIP and scan the `META-INF/plugin.xml` inside it for
placeholder / withdrawn-feature residue, check the id / version / vendor / `until-build`,
check that both icons are inside the JAR, and check that the change-notes start with the
shipped version (2026.1.7) and list at least one bullet (the older published versions may
follow as history). Also check that the ZIP you are about to upload is byte-for-byte the
one your build produced (`cmp` against `build/distributions/`), and that a version with the
same number is not already listed on the plugin page.

## Upload to JetBrains Marketplace

With `JETBRAINS_MARKETPLACE_TOKEN` set in the environment (the value is never written to
a file):

```bash
Z=build/distributions/ml-experiment-cockpit-2026.1.7.zip
md5sum "$Z"                       # the ZIP you upload must be the one your build produced
curl -i --header "Authorization: Bearer ${JETBRAINS_MARKETPLACE_TOKEN}" \
     -F pluginId=35416 \
     -F "file=@$Z" \
     https://plugins.jetbrains.com/api/updates/upload
```

The Marketplace reads the version, the release version and the change notes from the
descriptor inside the ZIP (`META-INF/plugin.xml`): there is no version field to fill in.
An empty `channel` means the *Stable* channel. After the upload, the update id returned by
the `201` response is the reference to keep, and the published version is checked on
`https://plugins.jetbrains.com/api/plugins/35416/updates` (public route, no token).

## Repository layout

```
ml-experiment-plugin/
├── build.gradle.kts                            IntelliJ Platform Gradle Plugin 2.7.2 (Kotlin DSL)
├── gradle.properties                           Plugin id, version, target IDE, vendor
├── settings.gradle
├── gradle/wrapper/                             Gradle 8.10.2 wrapper
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
│   │   ├── BaseUrl.kt                          The one place that says what a wrong server address is (2026.1.5)
│   │   └── pro/                                **Pro tier n°1 (v0.0.22)**
│   │       ├── ProGate.kt                      Pure-Kotlin licence gate (states PRO/ESSAI/EXPIRE/ABSENT/INCONNU)
│   │       ├── LicenceNatif.kt                 The ONLY file touching `com.intellij.ui.LicensingFacade`
│   │       ├── VerificationSceau.kt            Verification of the JetBrains signature of the licence stamp (2026.1.4)
│   │       ├── ComparaisonRuns.kt              Pure comparison core (params, metrics, gaps, Markdown)
│   │       ├── CourbeDiff.kt                   Superposed 2-run chart (A blue, B orange)
│   │       ├── ComparaisonHub.kt               Relay between the free tree and the Compare tab
│   │       ├── AcheteurPro.kt                  Seam to the platform purchase dialog (only place that opens it)
│   │       └── PanneauComparaison.kt           The “Compare” tab UI (locked without a licence)
│   └── resources/META-INF/
│       ├── plugin.xml                          Actions + services + notificationGroup + toolWindow
│       ├── pluginIcon.svg                      40×40 light-theme listing icon
│       └── pluginIcon_dark.svg                 40×40 dark-theme listing icon
├── LICENSE                                     Apache 2.0
└── README.md
```

## Support

The four main tabs are free to use. The optional Compare tab is a separate paid licence sold by the JetBrains Marketplace (see the plugin page).

- Plugin page (JetBrains Marketplace): https://plugins.jetbrains.com/plugin/35416-ml-experiment-cockpit
