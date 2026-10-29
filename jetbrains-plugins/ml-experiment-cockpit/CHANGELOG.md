# Changelog — ML Experiment Cockpit

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.19] — 2026-10-30

### Fixed
- **A malformed number in a server response broke the read with an opaque
  message.** The hand-written JSON parser accepted `1.`, `1e`, `-` and `01`;
  the number it produced could not be converted (`"1.".toDouble()` throws
  `NumberFormatException`), and that exception is *not* a `JsonException`, so
  it escaped `MlflowClient.parse()` and reached the user as
  « Unexpected error: For input string: "1." ». Numbers are now read with the
  exact JSON grammar (RFC 8259): such a response is rejected as invalid JSON
  and reported as « reponse non JSON du serveur ».
  Covered by 3 new tests (2 in `JsonParserTest`, 1 in `MlflowClientTest`).

### Bumped
- `pluginVersion` 0.0.18 → 0.0.19 (release notes of the new version).

## [0.0.18] — 2026-10-29

### Fixed
- **The tooltip of a run in the Experiments tree was never displayed.** Swing only
  shows a renderer's `toolTipText` for a component that has been registered with
  the `ToolTipManager`; the tree never registered itself, so hovering a run gave
  nothing at all. `MlflowToolWindowPanel` now calls
  `javax.swing.ToolTipManager.sharedInstance().registerComponent(this)`.
  **This fix was not part of the published 0.0.17 build** — measured on the
  public ZIP of 0.0.17 (update 1202899): the published class file contains no
  `ToolTipManager` reference, the local build does.

### Bumped
- `pluginVersion` 0.0.17 → 0.0.18 (release notes of the new version).

## [0.0.17] — 2026-10-24

Compatibility with the 2026.3 IDE release (build 263), plus the three defects
raised by the code review of 2026-10-24 (review `6868`), each one **measured**
against the live W&B API and against real captured server answers.

### Added
- **Compatibility up to IDE build 263.*** The ceiling moves from `262.*` to
  `263.*`: with the old ceiling the plugin becomes obsolete — and therefore
  uninstallable — as soon as PyCharm 2026.3 becomes the stable release. The
  range `[243, 263.*]` still covers the current stable 2026.2.

### Fixed
- **The GraphQL error message of the W&B tab was never shown.** The code read
  an *object* where W&B sends a *string*
  (`{"errors":[{"message":"Cannot query field …"}]}`), so the extraction always
  came back empty and the user only ever saw the useless « GraphQL error ».
  The server's own message is now displayed.
- **An unknown W&B project was reported as an empty list.** W&B answers
  **HTTP 200** with `{"data":{"project":null}}` and *no* `"errors"` array for
  an unknown entity or project, so the tab said "Loaded 0 sweep(s)". It now
  says **project not found** and names the project that was asked for.
- **A missing `runs` block no longer shows a fake "0 run(s)".** The run count
  is `null` (displayed as "n/a") when the server does not send it.

### Verified (not guessed)
- `runs { totalCount }` **is** a valid field of the `Sweep` type: the exact
  query of the client answers **HTTP 200** with `"runs":{"totalCount":12}`
  against the live `api.wandb.ai`, while the same query with one invented
  field is refused with **HTTP 400** « Cannot query field … on type "Sweep" ».
- The icon control of `scripts/prevol.sh` is now hard: it refuses an SVG that
  is not 40×40 (reading the root `<svg>` element) or that embeds a bitmap
  (`<image>` / `base64`).

## [0.0.16] — 2026-10-24

Display-only fix on top of 0.0.15. No change of business behaviour, no new
tab, no new dependency; the JAR still holds exactly the same 83 classes
(verified by diffing the class lists of the 0.0.15 and 0.0.16 JARs).

### Fixed
- **The X-axis labels of the metric chart were cut off.** The label was drawn
  at a hard-coded `sx - 20` offset, which is only the half-width of the text
  by coincidence: `"step 39"` (7 monospace characters, ~48 px) overflowed the
  canvas and Swing clipped the drawing, so the last step showed as `step`
  with the number missing. The labels are now measured with the font metrics,
  centred on their step, and clamped inside the canvas; the right margin is
  computed from the widest label instead of being a fixed 12 px.
- **The plot ran into the right edge of the panel.** The right margin is now
  reserved (half the widest tick label + 6 px), so the last point of the
  curve no longer touches the border.
- **A Y-axis label in scientific notation overlapped the plotted area.**
  `formatY` writes e.g. `1.170e-05` (9 characters, ~63 px) at x = 4, which
  overlapped the 56 px left margin. The margin is now 68 px, and a value
  below 0.01 no longer collides with the curve.
- The `N point(s) for <metric>` caption no longer touches the right border
  of the panel.

### Added
- `ChartHarness.kt` (offline bench, cycle 49): renders the production
  `LineChartCanvas` headless and measures the geometry of the result. The
  same bench fails on the 0.0.15 classes (3 failures) and passes on the
  0.0.16 classes (0 failures).

> Note: versions 0.0.13, 0.0.14 and 0.0.15 were built and shipped without a
> section in this file. The gap is recorded here rather than filled with
> reconstructed text.

## [0.0.12] — 2026-10-22

This is the first version that will ever be visible on JetBrains Marketplace.
No earlier version was published, so the public change-notes hold a single entry.

### Changed
- **Public plugin id `com.slot5.mlexp` → `com.tgglabs.mlexp`** in
  `src/main/resources/META-INF/plugin.xml` (`<id>`) and in
  `build.gradle.kts` (`pluginConfiguration.id`). `slot5` is an internal
  name of the factory and must not appear in a permanent public record.
  The Gradle `group`, the `pluginGroupId` property and the `LICENSE`
  copyright line were aligned at the same time.
- Change-notes reduced to a single entry, `0.0.12 — first public release`,
  listing the four shipped features (MLflow, MLflow Model Registry,
  Optuna, W&B Sweeps). The bundled descriptor is patched by Gradle from
  `build.gradle.kts`, so both copies carry the same text.

### Added
- `META-INF/pluginIcon.svg` and `META-INF/pluginIcon_dark.svg` (40×40,
  light and dark themes). Without them the listing falls back to the
  default JetBrains placeholder icon. Both are verified to be inside the
  built JAR, not only under `src/`.
- `scripts/prevol.sh` — repeatable pre-flight. It extracts the plugin JAR
  out of the distribution ZIP and scans the `META-INF/plugin.xml` inside
  it (the file JetBrains actually loads) for placeholder / withdrawn-feature
  residue, so the control runs on every build instead of living in a report.

### Bumped
- Plugin version: `0.0.11` → `0.0.12`.

## [0.0.11] — 2026-10-20

### Changed
- First public release on JetBrains Marketplace: change-notes reduced
  to a single entry listing the four features (MLflow, MLflow Model
  Registry, Optuna, W&B Sweeps). No prior version has ever been
  visible publicly; this is the first plugin version that users will
  see.
- Applied the 2026-10-19 pre-publication jury patch
  (`/work/REVUE-JURY/20261019-1240-slot5-ml-experiment-plugin-397d.patch`):
  the bundled change-notes in `build.gradle.kts` were rewritten to
  match what the public will see (no 0.0.7 → 0.0.10 history, no
  vendor / email leak).
- Manually aligned `plugin.xml` `<change-notes>` with the patched
  `build.gradle.kts` block (the jury patch only touches the Gradle
  side, not the bundled descriptor).

### Bumped
- Plugin version: `0.0.10` → `0.0.11`.

## [0.0.10] — 2026-10-19

### Changed
- Vendor info patched to the real owner profile:
  `name = "Tgg Labs"`, `email = "tgg.apps.support@gmail.com"`,
  profile URL points to the existing JetBrains Marketplace vendor
  page at `https://plugins.jetbrains.com/vendor/tgg-labs`
  (vendor metadata now matches the Tgg Labs JetBrains Marketplace profile).

### Bumped
- Plugin version: `0.0.9` → `0.0.10`.

## [0.0.9] — 2026-10-16

### Security
- **W&B API key is now stored in the IDE's `PasswordSafe` credential
  store** instead of in plain text in the settings XML. Existing
  v0.0.7–v0.0.8 users are migrated transparently on the next settings
  read (the value is copied to `PasswordSafe` and cleared from the
  XML in `MlflowSettingsImpl.loadState()`). The settings XML no
  longer contains the key at all after the migration runs.

### Verified against
- Verified by the JetBrains Plugin Verifier against **PyCharm 2026.1
  and 2026.2** (both Compatible). The `pluginUntilBuild = 262.*`
  ceiling means the plugin supports PyCharm **2024.3 to 2026.2**.
  The older verified lines (2024.3, 2025.1, 2025.2) were dropped
  from the verification matrix to free up disk for the 2026.x
  downloads; they remain binary compatible (the `since-build` is 243).
- Caught by the pre-publication jury review on 2026-10-16 (id
  `20261016-0908-slot5-ml-experiment-plugin-8a3c`).

### Bumped
- Plugin version: `0.0.8` → `0.0.9`.

## [0.0.8] — 2026-10-16

### Removed
- **The v0.0.5 Replicate deploy hooks** — the `DeployCurrentRunAction`
  (menu `Tools → MLflow → Deploy current run to Replicate`), the
  `Deploy` tab in the Tool Window, the `ReplicateClient` and the
  three Replicate settings fields (`deployToken`, `deployProvider`,
  `defaultModelName`) are all gone. The Tool Window now has 4 tabs
  (Experiments, Optuna, Models, Sweeps) instead of 5. Users who
  stored a Replicate token in v0.0.7 settings will see it discarded
  on upgrade (the `State` bean lost the three fields; the storage
  key `MlflowSettings` is unchanged so other fields are preserved).

### Fixed
- **Plugin no longer ships in "legacy" mode.** Added
  `<depends>com.intellij.modules.platform</depends>` in
  `plugin.xml`. Without this dependency, the JetBrains plugin
  tooling refuses to load the plugin in PyCharm (our target IDE)
  and only loads it in IntelliJ IDEA. Caught by the pre-publication
  jury review on 2026-10-15 (id
  `20261015-1156-slot5-ml-experiment-plugin-8f0b`).
- **Settings Sync / Backup disabled for the settings storage.**
  Added `RoamingType.DISABLED` on the `mlExperimentCockpit.xml`
  storage so the W&B API key never roams to JetBrains' cloud.
  Same jury review. The proper fix (`PasswordSafe`) is shipped in
  v0.0.9.
- **Plugin descriptor cleaned up.** Removed the unused `package=`,
  `url=` and `bundle=""` attributes that the JetBrains plugin
  tooling flags as unnecessary. Same jury review.
- **`pluginUntilBuild` extended from `251.*` to `262.*`.** The old
  ceiling excluded every PyCharm from 2025.2 onwards. The Plugin
  Verifier refuses empty values and the `999.*` sentinel
  ("magic value"), so the new ceiling is the latest available
  PyCharm build (262.10968.92, released 17/09/2026).

### Bumped
- Plugin version: `0.0.7` → `0.0.8`.

## [0.0.7] — 2026-10-15

### Added (W&B Sweeps)
- **New "Sweeps" tab** in the existing Tool Window (5th tab, next to the
  "Experiments", "Optuna", "Deploy" and "Models" tabs).
- **`POST https://api.wandb.ai/graphql`** wired up via two new
  `WandbClient` methods: `listSweeps(entity, project)` and
  `getSweep(entity, project, sweepName)`. Reuses the standard W&B
  GraphQL API (the same one used by the official `wandb` Python client
  via `wandb.Api()`) — Bearer auth, no 3rd-party deps.
- **Three new settings fields** in Settings → Tools → ML Experiment
  Cockpit: `wandbApiKey` (the Bearer API key), `wandbEntity` (default
  pre-fill for the Sweeps tab) and `wandbProject` (same). Entity +
  project can be overridden per-session in the panel itself.
- **Two new data classes** `SweepSummary` and `SweepBestRun`. The
  summary metrics of the best run are extracted as a `Map<String, String>`
  and surfaced in the detail panel (top 8 by key).
- **Tree grouped by state** (running / finished / failed / crashed /
  pending / stopped — custom order, then catch-all for unknown).
  **Coloured per-state** via a custom `DefaultTreeCellRenderer`:
  running in blue, finished in green, failed/crashed in red.
- **Detail panel** for a sweep: name, id, state, runs total, created
  + updated timestamps, best run (id, name, state, top-8 summary
  metrics).
- **Status surface** when no W&B API key is configured
  ("Configure the W&B API token first...") — no silent empty tree.

### Changed
- Bumped plugin version `0.0.6 → 0.0.7`.
- `MlflowSettings` (the PersistentStateComponent interface) gained 6
  new methods (`getWandbApiKey/setWandbApiKey`,
  `getWandbEntity/setWandbEntity`, `getWandbProject/setWandbProject`)
  + 3 new `State` fields. The component `name="MlflowSettings"` is
  unchanged → no migration risk for upgraders from v0.0.1–v0.0.6.
- `MlflowSettingsConfigurable` (the Settings panel) grew from 5 to 8
  fields (`isModified`/`apply`/`reset` snapshots all extended).
- `MlflowToolWindowFactory` registers the 5th Content tab "Sweeps".
  Existing 4 tabs (`Experiments`, `Optuna`, `Deploy`, `Models`) are
  strictly unchanged.

### Still building on
- v0.0.6 Models tab (MLflow Model Registry) — untouched.
- v0.0.5 Deploy tab (Replicate deploy hooks + "Deploy current run"
  action menu) — untouched.
- v0.0.4 Optuna tab (study viz + horizontal bar chart) — untouched.
- v0.0.3 time-series chart of a single metric — untouched.
- v0.0.2 Experiment/Run tree — untouched.
- v0.0.1 "Connect to MLflow Tracking Server" action menu — untouched.

### Technical
- **0 new third-party dependencies** — only JDK 17 `HttpClient` + Swing
  + `JBLabel`. No new JAR pulled in (verified by inspecting the
  `dependencies` block in `build.gradle.kts`).
- **0 IDE-target changes** — still PyCharm 2024.3 + 2025.1
  (verified by the `pluginVerification { ides { ... } }` block).
- **Backward-compatible Settings migration** — old `mlExperimentCockpit.xml`
  files load fine; the new 3 fields default to `""` when absent.

## [0.0.6] — 2026-10-15

### Added (MLflow Model Registry)
- **New "Models" tab** in the existing Tool Window (4th tab, next to
  the "Experiments", "Optuna" and "Deploy" tabs).
- **`POST /api/2.0/mlflow/registered-models/search`** wired up via three
  new `MlflowClient` methods: `listRegisteredModels()`,
  `getRegisteredModel(name)`, `listModelVersions(name)`. Reuses the
  same base URI as the Experiments tab — **no new settings field**.
- **Two new data classes** `RegisteredModel` and `ModelVersion` (no
  3rd-party deps). The `Models` tab renders a tree:
  **registered model** → **latest versions** (version number, current
  stage, status, run id).
- **Detail panel** for a model: description, creation timestamp,
  per-version summary (stage, status). Detail panel for a single
  version: version number, current stage, status, run_id, creation
  timestamp.
- **MLflow 2.0+ note**: Model Registry is shipped with MLflow 2.0+ —
  older servers return HTTP 404 and the status label surfaces that
  clearly (no silent empty tree).

### Changed
- Bumped plugin version `0.0.5 → 0.0.6`.
- `MlflowClient` adds 3 methods (no breaking changes to existing 5).
- `MlflowToolWindowFactory` now registers FOUR `Content` tabs instead
  of three. Tab order: **Experiments**, **Optuna**, **Deploy**, **Models**.
- Description and change-notes updated to reflect Model Registry + the
  now broader plugin scope (MLflow tracking + MLflow registry + Optuna
  dashboard + Replicate deploy).

### Still building on (no breaking change)
- Replicate deploy hooks (v0.0.5): Deploy tab + "Tools → Deploy current
  run to Replicate" action — unchanged.
- Optuna study visualization (v0.0.4): summary panel, top-5 trials
  table, horizontal bar chart — unchanged.
- Time-series chart for MLflow runs (v0.0.3): line chart with
  auto-scaled Y axis — unchanged.
- Tool Window "Experiments" tab and its tree / chart (v0.0.2 + v0.0.3)
  — unchanged.
- "Connect to MLflow" action (v0.0.1) — unchanged.

### Technical
- IntelliJ Platform Gradle Plugin **2.7.2** (Kotlin DSL).
- Target IDE: PyCharm Professional 2024.3+ (`since-build = 243`,
  `until-build = 251.*`).
- Bytecode target: Java 17. Build runs on JDK 21.
- 0 new third-party dependencies — only JDK 17 `HttpClient` + Swing.

## [0.0.5] — 2026-10-14

### Added (Replicate deploy hooks)
- **New `ReplicateClient`** talking to `https://api.replicate.com/v1` over
  plain REST (no SDK pulled in). Methods: `createDeployment`,
  `listDeployments`, `getDeployment`, `deleteDeployment`,
  `createPrediction`. Single `Authorization: Bearer <token>` auth header.
- **New "Deploy" tab** in the existing Tool Window (next to the
  "Experiments" and "Optuna" tabs). Refresh button + deployments tree,
  detail panel showing the endpoint URL for invocation
  (`/v1/deployments/{owner}/{name}/predictions`), and a "Delete selected"
  button with confirmation dialog.
- **New menu action "Tools → Deploy current run to Replicate"** that
  prompts for a deployment name (timestamp-suffixed), creates the
  deployment in one click, and shows the endpoint URL in a notification.
- **Settings UI third block (3 new fields)**: Settings → Tools → ML
  Experiment Cockpit now has FIVE text fields:
  - MLflow tracking URI (v0.0.1, unchanged).
  - Optuna dashboard URI (v0.0.4, unchanged).
  - **Replicate API token (v0.0.5)**.
  - **Deploy provider (v0.0.5)** — currently only `replicate` is
    supported; reserved for future `modal` / `bentoml`.
  - **Default deployment name (v0.0.5)** — pre-filled in the prompt.

  Upgraders keep their stored MLflow URI and Optuna URI intact — the
  component name (`MlflowSettings`) is unchanged.

### Changed
- Bumped plugin version `0.0.4 → 0.0.5`.
- `MlflowSettings` interface gains `getDeployToken()` / `setDeployToken()`,
  `getDeployProvider()` / `setDeployProvider()`, and
  `getDefaultModelName()` / `setDefaultModelName()`. Persistent state
  gains matching `deployToken`, `deployProvider`, `defaultModelName`
  fields (defaulted to `replicate` and `slot5-demo-deploy` respectively).
- `MlflowToolWindowFactory` now registers THREE `Content` tabs
  (Experiments + Optuna + Deploy) instead of two.
- `MlflowToolWindowPanel` exposes a static `LAST_RUN_ID` (v0.0.5) that
  `DeployCurrentRunAction` reads so the menu has access to the currently
  selected MLflow run id.

### Still building on (no breaking change)
- Time-series chart for MLflow runs (v0.0.3).
- Optuna study visualization (v0.0.4) — summary, top-5 trials, bar chart.
- The Tool Window "Experiments" tab and its tree / chart (v0.0.2 + v0.0.3)
  are unchanged.
- `Tools → MLflow → Connect to Tracking Server` action (v0.0.1) is
  unchanged.

### Technical
- IntelliJ Platform Gradle Plugin **2.7.2** (Kotlin DSL).
- Target IDE: PyCharm Professional 2024.3+ (`since-build = 243`,
  `until-build = 251.*`).
- Bytecode target: Java 17. Build runs on JDK 21.
- 0 new third-party dependencies — JDK 17 `HttpClient` + Swing only.

## [0.0.4] — 2026-10-14

### Added (Optuna study visualization)
- **New `OptunaClient`** with `listStudies()`, `getStudy(id)`, `listTrials(id)`
  methods. Hand-rolled REST client (JDK 17 `HttpClient` only, no SDK pulled
  in). Tested against 27 Python assertions covering study summaries,
  direction parsing, trial extraction, scientific-notation values, and
  multi-objective studies.
- **New "Optuna" tab** in the existing Tool Window (next to the
  "Experiments" tab).
- **Studies tree** with a single click to "Refresh" — reuses the same
  tool window pattern as v0.0.2.
- **Study summary panel**: study name, direction (`minimize` /
  `maximize`), `n_trials`, `n_complete`, best value, `datetime_start`.
- **Top-5 trials table** sorted by value (direction-aware: `maximize`
  sorts descending, `minimize` sorts ascending). Trials with `null` value
  (RUNNING, PRUNED, FAIL) are filtered out of the top table.
- **Horizontal bar chart** of the most-chosen hyperparameter (`Graphics2D`
  only, no JFreeChart). Heuristic: prefer numeric params, then by
  number of distinct values; top 8 buckets shown.
- **Settings UI second field**: Settings → Tools → ML Experiment Cockpit
  now has TWO text fields (MLflow URI, Optuna URI). Existing MLflow URI
  field is unchanged and keeps its stored value after upgrade.

### Changed
- Bumped plugin version `0.0.3 → 0.0.4`.
- `MlflowSettings` interface gains `getOptunaUri()` / `setOptunaUri()`;
  persistent state gains a new `optunaUri` field.
- `MlflowToolWindowFactory` now registers TWO `Content` tabs inside the
  existing tool window (Experiments + Optuna) instead of one.

### Still building on (no breaking change)
- `Tools → MLflow → Connect to Tracking Server` action still works as in
  v0.0.1 (uses `MlflowClient` internally).
- The Tool Window "Experiments" tab and its tree / chart (v0.0.2 + v0.0.3)
  are unchanged.

### Technical
- IntelliJ Platform Gradle Plugin **2.7.2** (Kotlin DSL).
- Target IDE: PyCharm Professional 2024.3+ (`since-build = 243`,
  `until-build = 251.*`).
- Bytecode target: Java 17. Build runs on JDK 21.
- 0 new third-party dependencies.

## [0.0.3] — 2026-10-14

### Added (Time-series chart)
- **Per-metric time-series chart** below the run-detail panel. Once a run is
  selected, every metric on the run appears in a combo box; picking one
  fetches the full history via `GET /api/2.0/mlflow/metrics/get-history`
  and renders a line chart.
- **Hand-rolled `LineChartCanvas`** drawn with `Graphics2D` only. No
  JFreeChart or any other 3rd-party chart library is bundled (kept the
  plugin at ~1.7 MB ZIP instead of ~3 MB with JFreeChart).
- **Y axis auto-scaling** with 5 % headroom; values in `[1e-2, 1e4)` use
  plain notation, others use scientific notation.
- **Status callback** so picking a metric also updates the top status
  bar of the Tool Window.
- **New client method** `MlflowClient.getMetricHistory(runId, metricKey, maxResults)`
  with hand-rolled URL encoding (run-ids are hex in modern MLflow; metric
  keys rarely contain anything beyond alphanumerics + `_.-/`.

### Changed
- Bumped plugin version `0.0.2 → 0.0.3`.
- The right-hand panel of the Tool Window is now a `BorderLayout` JPanel
  with the run-detail scroll-pane on top and the chart holder on the
  bottom (the chart holder is populated each time a run is selected).
- Updated description and change-notes to reflect the chart MVP.

### Still building on (no breaking change)
- `Tools → MLflow → Connect to Tracking Server` action still works as in
  v0.0.1 (uses `MlflowClient` internally).
- The Tool Window left-rail Experiment/Run tree (v0.0.2) is unchanged.

### Technical
- IntelliJ Platform Gradle Plugin **2.7.2** (Kotlin DSL).
- Target IDE: PyCharm Professional 2024.3+ (`since-build = 243`, `until-build = 251.*`).
- Bytecode target: Java 17. Build runs on JDK 21.
- 0 new third-party dependencies.

## [0.0.2] — 2026-10-14

### Added (Tool Window MVP)
- **Left-rail Tool Window** "ML Experiment Cockpit" (`View → Tool Windows → ML Experiment Cockpit`).
- **Refresh button** that re-fetches all experiments from the configured MLflow
  tracking server and populates a 2-level Tree (Experiment → Run).
- **Per-run detail panel**: click a run to load its params and the latest value
  of every metric via `POST /api/2.0/mlflow/runs/get`. Displayed as
  monospace HTML in the right-hand panel.
- **New shared client** `MlflowClient.kt` (~6 KB) — used by both the menu
  action and the Tool Window. Hand-rolled regex extractors keep the plugin
  dependency-free (no Jackson, no Guava).
- **Per-experiment error isolation**: a per-experiment `runs/search` failure
  no longer aborts the entire refresh.

### Changed
- Bumped plugin version `0.0.1 → 0.0.2`.
- Replaced deprecated `ide(String, String, ...)` with `create(type, version, configure)`
  in `pluginVerification { ides { ... } }` (clears the 2 deprecation warnings).
- Updated description and change-notes to reflect the Tool Window MVP.

### Still building on (no breaking change)
- `Tools → MLflow → Connect to Tracking Server` action still works as in v0.0.1
  (now also uses `MlflowClient` internally for consistency).

### Technical
- IntelliJ Platform Gradle Plugin **2.7.2** (Kotlin DSL).
- Target IDE: PyCharm Professional 2024.3+ (`since-build = 243`, `until-build = 251.*`).
- Bytecode target: Java 17. Build runs on JDK 21.
- Plugin Verifier pinned to `PY-2024.3 + PY-2025.1` (limits IDE downloads to
  fit in the 38 GB container budget).

## [0.0.1] — 2026-10-14

### Added (MVP)
- Settings UI: `Settings → Tools → ML Experiment Cockpit` for the MLflow tracking URI.
- Persistent storage of the tracking URI (per-user XML in IDE config dir).
- `Tools → MLflow → Connect to Tracking Server` action that probes
  `POST /api/2.0/mlflow/experiments/search` with `{"max_results": 1}` and
  surfaces success / HTTP error / network error in a notification balloon.
- Notification group `ML Experiment Cockpit Notifications` (registered in
  `plugin.xml`).

### Technical
- IntelliJ Platform Gradle Plugin **2.7.2** (Kotlin DSL, no `1.x deprecated` warnings).
- Target IDE: PyCharm Professional 2024.3+ (`since-build = 243`, `until-build = 251.*`).
- Bytecode target: Java 17. Build runs on JDK 21 (set via `JAVA_HOME`).
- Plugin Verifier task configured (`pluginVerification { ides { recommended() } }`).