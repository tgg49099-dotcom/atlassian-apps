// ML Experiment Cockpit — build script.
//
// Slot 5 (JetBrains Marketplace) business mission, second plugin.
//
// Built on the same IntelliJ Platform Gradle Plugin 2.7.2 stack as
// /work/hello-world-plugin (validated cycle 3). Targets PyCharm Professional
// (platformType = PY) as the primary IDE for ML workflows; DataSpell (DS) is
// listed as an optional secondary target.
//
// References:
//   - Migration guide: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-migration.html
//   - 2.x user guide : https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
//   - 2.x types      : https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-types.html
//
// Key changes vs hello-world:
//   - platformType = "IC"  -> "PY"  (PyCharm Professional for ML workflows)
//   - product descriptor targets ML/data-scientist audience.
//   - plugin id / name / description tailored to MLflow/Optuna/Ray tracking.

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.7.2"
    kotlin("jvm") version "1.9.25"
}

// 26/10/2026 (cycle 58) : types du DSL `pluginVerification.ides.select { }`
// (canal EAP). Sans ces imports, `select` n'existe pas dans le script.
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

group = "com.tgglabs.mlexp"
version = (project.findProperty("pluginVersion") as String?) ?: "0.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // PyCharm Professional — primary IDE target for ML workflows.
        // For 2024.3, code "PY" maps to PyCharmProfessional (community-built).
        create("PY", (project.findProperty("platformVersion") as String?) ?: "2024.3")

        // Uncomment to also target DataSpell (JetBrains' dedicated data-science IDE).
        // Note: adding a second IDE roughly doubles the verifyPlugin wall-clock cost.
        // create("DS", (project.findProperty("platformVersion") as String?) ?: "2024.3")
    }

    // 29/10/2026 (cycle 82) : dependance de TEST.
    //
    // Les deux fichiers de `src/test/kotlin/` (JsonParserTest.kt,
    // WandbClientTest.kt) utilisent JUnit 5 (`org.junit.jupiter.api.*`), mais
    // le script ne declarait AUCUNE dependance de test : `gradle test`
    // echouait donc a la COMPILATION des tests avec
    //   "Unresolved reference: junit" / "Unresolved reference: assertEquals",
    // et les tests n'ont jamais ete executes par Gradle.
    // (Les tests "hors-ligne" du cycle 54, 63 assertions, etaient un banc
    // Python/console separe, pas la tache `:test` de Gradle.)
    //
    // Les tests ne dependent que du JDK (le faux serveur W&B est un
    // `com.sun.net.httpserver.HttpServer`) : JUnit 5 seul suffit, le
    // framework de test d'IntelliJ n'est pas necessaire.
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    // JUnit 4 : l'initialiseur d'environnement de test d'IntelliJ
    // (`com.intellij.testFramework.junit5.impl.Junit5TestEnvironmentInitializer`,
    // appele par le `LauncherSession` de la plateforme JUnit) construit un
    // `Logger` de test framework qui IMPLEMENTE `org.junit.rules.TestRule`,
    // une classe de JUnit 4. Sans JUnit 4 sur le classpath de test, la tache
    // `:test` meurt au demarrage du worker :
    //   java.lang.NoClassDefFoundError: org/junit/rules/TestRule
    //   Caused by: java.lang.ClassNotFoundException: org.junit.rules.TestRule
    testRuntimeOnly("junit:junit:4.13.2")
}

// JUnit 5 : Gradle ne lance la plateforme JUnit que si on le demande
// explicitement (sinon il ne trouve aucun moteur de test et la tache `:test`
// ne fait rien, sans erreur visible).
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// Configure the IntelliJ Platform Gradle Plugin.
intellijPlatform {
    pluginConfiguration {
        id = "com.tgglabs.mlexp"
        name = "ML Experiment Cockpit"
        // 07/11/2026 (cycle 150) — version AFFICHEE / VENDUE.
        // MESURE : cette ligne (`pluginConfiguration.version`) est ce qui
        // decide de la balise <version> du plugin.xml FINAL (le patcher de la
        // plateforme ecrase la balise <version> des sources). Elle vaut donc
        // `pluginMarketplaceVersion` (2026.1.1) et NON plus `project.version`
        // (0.0.23) : la regle officielle impose que les 2 premiers chiffres du
        // `release-version` ("20261") correspondent a ceux du `version`.
        // `project.version` reste 0.0.23 : c'est lui qui nomme le ZIP livre
        // (ml-experiment-cockpit-0.0.23.zip) — inchange. Le nom du JAR INTERNE
        // du ZIP, lui, suit `pluginConfiguration.version` :
        // ml-experiment-cockpit-2026.1.1.jar (MESURE cycle 150, voir
        // gradle.properties) — pas 0.0.22. C'est ce que lit aussi
        // scripts/prevol.sh (depuis le cycle 153).
        version = (project.findProperty("pluginMarketplaceVersion") as String?) ?: project.version.toString()
        ideaVersion {
            sinceBuild = (project.findProperty("pluginSinceBuild") as String?) ?: "243"
            untilBuild = (project.findProperty("pluginUntilBuild") as String?) ?: "263.*"
        }
        // 07/11/2026 (cycle 152) — ATTENTION : ce bloc est la SOURCE REELLE
        // de la description et des notes de version livrees. Le patcher du
        // plugin IntelliJ Platform 2.x (patchPluginXml) ECRASE la balise
        // <description> et <change-notes> du plugin.xml SOURCE par celles
        // d'ici : editer le plugin.xml source seul ne change RIEN dans le ZIP
        // livre (MESURE cycle 152 : le ZIP construit gardait l'ancienne
        // phrase trompeuse alors que le plugin.xml source etait corrige).
        // Les deux fichiers (plugin.xml source ET ce bloc) doivent donc
        // porter EXACTEMENT le meme texte.
        description = """
<p>MLflow experiment tracking, Weights & Biases (wandb) sweeps, Optuna studies, the Model Registry — runs and metrics in PyCharm, IntelliJ, WebStorm, GoLand or CLion.</p>
<ul>
<li><b>Experiments</b> — your MLflow experiments and runs in a tree, with parameters, metric values and the full history of any metric as a curve.</li>
<li><b>Optuna</b> — studies and trials from your Optuna Dashboard: summary, the five best trials and a bar chart of the hyperparameter that takes the most distinct values.</li>
<li><b>Models</b> — the MLflow Model Registry: registered models, their versions, stage, status and run id.</li>
<li><b>Sweeps</b> — W&B hyperparameter sweeps grouped by state, with their run count and timestamps (the W&B API does not expose a best run for a sweep).</li>
</ul>
<p>All five tabs (Experiments, Optuna, Models, Sweeps and the Compare view) live in one <b>ML Experiment Cockpit</b> tool window, in PyCharm, IntelliJ IDEA, WebStorm, GoLand and CLion — any IntelliJ-platform IDE from build 243 to 263. This plugin is <b>read-only</b>: it reads your servers and never writes to them.</p>
<p>You see exactly what your server answers, within its own query limits: up to 500 experiments, 100 runs per experiment, 500 registered models, 500 model versions and 5 000 metric points per run; W&B sweeps are read without pagination (the first 50 sweeps).</p>
<table>
<tr><th>Function</th><th>Free</th><th>Pro ($29/year, free trial)</th></tr>
<tr><td>Experiments, Optuna, Models, Sweeps tabs</td><td>yes</td><td>yes</td></tr>
<tr><td>Compare 2 runs: parameters, metrics with gaps, both curves on one axis, Markdown export</td><td>tab visible, locked</td><td>yes</td></tr>
<tr><td>Metric points per metric, per run</td><td>5 000 points (free chart)</td><td>100 000 points (in the Compare view)</td></tr>
</table>
<p><b>ML Experiment Cockpit Pro</b> adds exactly one tab — <b>Compare</b>: right-click a run in the Experiments tree, pick « Compare with… », and the two runs are shown side by side — parameters, last metric values with absolute and relative gaps — and both metric curves are drawn on the same axis, exportable as Markdown. Without a licence the tab stays visible but locked, and the four free tabs are unchanged.</p>
<p>It is a read-only MLOps view: experiment tracking, hyperparameter sweeps and registered models, read from the servers you configure. Open it from <b>View | Tool Windows | ML Experiment Cockpit</b> and set your servers in <em>Settings | Tools | ML Experiment Cockpit</em>. Every request goes to the servers you configure — no telemetry, no analytics, no advertising.</p>""".trimIndent()
        changeNotes = """
<h3>2026.1.1 - Compare tab: the real price, the licence read again, and the platform's pooled threads.</h3>
<ul>
    <li>The locked Compare tab now states the price set on the Marketplace: $29/year, and mentions the free trial without promising a duration (the trial length is the one set in the Marketplace sale settings).</li>
    <li>The licence state is read again every time the Compare tab becomes visible again (for instance when the IDE had just started and the licence could not be read yet), so the tab is not left locked for a customer who has a licence.</li>
    <li>The status line is now shown under both views of the tab, so a purchase dialog that cannot be opened is reported instead of failing silently.</li>
    <li>If the second run is changed while the comparison is being computed, the comparison is computed again for the new run instead of showing the previous one.</li>
    <li>A late answer for a metric that is no longer selected is ignored, and the metric list is filled without loading the history several times.</li>
    <li>All the background requests now run on the IDE's pooled threads instead of raw threads, as recommended by JetBrains.</li>
    <li>The wording of the paid tier in the interface has been corrected (an ML Experiment Cockpit Pro licence).</li>
</ul>
<h3>2026.1.0 — Pro tier: Compare 2 runs.</h3>
<ul>
    <li>A fifth tab, <b>Compare</b>: right-click a run in the Experiments tree and choose « Compare with… ». The two runs are shown side by side — parameters, last metric values with absolute and relative gaps — and both metric curves are drawn on the same axis.</li>
    <li>The whole comparison can be copied as <b>Markdown</b> (both tables) with one click.</li>
    <li>Pro only: in the Compare view, a run's metric history goes up to 100 000 points per metric (the free chart stays at 5 000 points per metric; the interface then says the series is truncated).</li>
    <li>Without an ML Experiment Cockpit Pro licence, the Compare tab stays visible but locked, with a « Get Pro » button that opens the JetBrains purchase dialog. The four free tabs — Experiments, Optuna, Models, Sweeps — are unchanged, with the same limits as before (500 experiments, 100 runs per experiment, 500 registered models, 500 model versions, 5 000 metric points per run).</li>
</ul>
<h3>0.0.21 — New description, easier to find.</h3>
<ul>
    <li>The plugin description on the Marketplace has been rewritten: it now names the four tabs (Experiments, Optuna, Models, Sweeps) and the servers they read, so the plugin shows up in searches that use the words buyers actually type.</li>
    <li>No functional change; includes the fixes of 0.0.18–0.0.20 below.</li>
</ul>
<h3>0.0.20 — Four display and read defects fixed.</h3>
<ul>
    <li>Optuna and Models trees now show the readable label of each row (study name, model name, version) instead of the raw Kotlin dump (<code>StudyNode(summary=…)</code>).</li>
    <li>The Optuna bar chart now picks the hyperparameter that takes the most distinct values, as documented; the numeric tie-breaker is only used when two parameters have the same number of values.</li>
    <li>A mistyped Optuna URI (for example <code>localhost:8080</code> without <code>http://</code>) is now reported as <code>Failed to load studies</code> instead of <code>Unexpected error</code>.</li>
    <li>Optuna trials whose value is <code>inf</code> or <code>-inf</code> (sent as a string by the dashboard) are now read as <code>Infinity</code> and <code>-Infinity</code>; they used to show as “—” and to be left out of the ranking.</li>
</ul>
<h3>0.0.19 — Malformed numbers are reported as invalid JSON.</h3>
<ul>
    <li>Numbers are now read with the exact JSON grammar (RFC 8259). A response holding a malformed number (for example <code>1.</code>, <code>1e</code> or <code>01</code>) is rejected as invalid JSON: the plugin now says that the server did not answer JSON, instead of failing with an opaque conversion error such as <code>For input string: "1."</code>.</li>
</ul>
<h3>0.0.18 — Run tooltips now show.</h3>
<ul>
    <li>The tooltip of a run in the Experiments tree is now displayed when you hover it: Swing only shows a renderer's tooltip for a component registered with the ToolTipManager, so the run details were silently never shown. This fix was not part of the 0.0.17 build.</li>
</ul>
<h3>0.0.17 — Compatible with the 2026.3 IDE release, and clearer W&amp;B errors.</h3>
<ul>
    <li>The plugin now declares compatibility up to IDE build 263.*, so it keeps installing on PyCharm 2026.3 instead of becoming obsolete when that release becomes stable.</li>
    <li>W&amp;B tab: a GraphQL error now shows the server's own message (e.g. "Cannot query field …") instead of a bare "GraphQL error", and an unknown project is reported as "project not found" instead of an empty list.</li>
    <li>The MLflow and Optuna tabs are unchanged.</li>
</ul>""".trimIndent()
        vendor {
            name = "Tgg Labs"
            email = "tgg.apps.support@gmail.com"
            url = "https://plugins.jetbrains.com/vendor/tgg-labs"
        }
    }
    signing {
        certificateChain = System.getenv("CERTIFICATE_CHAIN_FILE")
        privateKey = System.getenv("PRIVATE_KEY_FILE")
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = System.getenv("JETBRAINS_MARKETPLACE_TOKEN")
    }
    // Required for `:verifyPlugin` (the IntelliJ Platform Gradle Plugin task).
    // Without this block, the task fails with "No IDE resolved for verification".
    //
    // CONSIGNE 51 (23/10/2026) : "Lance-le sur AU PLUS 2 versions d'IDE
    // (la plus ancienne visee et la plus recente), pas plus : le disque et le
    // cache Gradle paient cher."
    //
    // Matrice retenue - les DEUX extremes de la plage declaree :
    //   - 2024.3 : build 243.21565.199 = le PLANCHER `since-build 243`.
    //   - 2026.2 : build 262.10968.92  = le PLAFOND `until-build 262.*`.
    // Un plugin compatible aux deux bouts l'est partout entre les deux.
    // C'est aussi le test le plus utile : la 0.0.13 ne touche que du Kotlin
    // pur, mais c'est exactement ce genre de changement qui casse d'abord
    // sur le build le plus ancien.
    //
    // 2026.1 et les lignes 2025.x ne sont plus dans la matrice : chaque ligne
    // coute ~3 Go d'extraction PyCharm et le conteneur n'a que 9,4 Go libres.
    // (2024.3 + 2025.1 avaient ete verifies en v0.0.8 ; 2025.2 en v0.0.8 +
    // cycle 17 ; 2026.1 n'a jamais donne de verdict sur la 0.0.13.)
    pluginVerification {
        ides {
            // `ide(String, String, ...)` is deprecated in 2.x. The 2.x replacement
            // is `create(type, version, configure)` with a configuration lambda.
            // We don't need any IDE-specific configuration here, so an empty
            // lambda suffices - but the deprecation warning goes away.
            create("PY", "2024.3") {}
            //
            // 26/10/2026 (cycle 58) : le PLAFOND declare est `until-build 263.*`,
            // donc l'IDE 2026.3 (build 263) DOIT figurer dans la matrice de
            // verification. C'est exactement ce que le jury `a80c` reprochait :
            // la matrice ne testait que 2024.3 et 2026.2, donc jamais le 263
            // que la version en ligne annonce.
            //
            // On ne met PAS une 3e IDE (le conteneur n'a que ~5 Go libres et une
            // IDE PyCharm occupe ~3,9 Go extrait) : `select { channels = EAP }`
            // remplace 2026.2 par l'EAP 2026.3 (build 263), ce qui garde DEUX
            // IDE au total et UNE seule passe de verification.
            //
            // Mesure du cycle 58 : l'EAP n'est PAS publie dans le
            // intellij-repository (404 sur `pycharmPC/2026.3`), il se resout
            // via l'API officielle des versions (data.services.jetbrains.com,
            // build 263.7307.50 du 23/10/2026) et se telecharge depuis
            // download.jetbrains.com.
            //
            // 07/11/2026 (cycle 153) — CORRECTION DE LA MATRICE DE VERIFICATION.
            // MESURE : `select { channels = EAP ; untilBuild = "263.*" }` NE filtre
            // PAS sur le canal : l'API officielle des versions renvoie TOUTES les
            // versions PyCharm Professional <= 263.* et le `select` les prend
            // toutes. Log de verite (preuves/cycle153/06-build-verify-cycle153.log) :
            //   "The verifyPlugin task is about to resolve 8 IDEs: python:pycharm-professional:2024.3,
            //    263.7307.101, 262.10315.63, 261.27258.30, 253.31033.74,
            //    252.26830.27, 251.26927.54, 243.23654.74"
            // 8 verifications en parallele -> `java.lang.OutOfMemoryError: Java heap
            // space` (lecture d'un jar d'IDE) -> `verifyPlugin` sort en ECHEC (RC 1).
            // C'est aussi contraire a la consigne 51 (« AU PLUS 2 versions d'IDE :
            // la plus ancienne visee et la plus recente »).
            // CORRECTION : `sinceBuild.set("263.*")` borne la selection au build
            // 263 -> la matrice redevient les DEUX extremes de la plage declaree
            // (plancher 243 / plafond 263), exactement ce que dit le commentaire
            // ci-dessus. Le comportement du plugin ne change pas : seule la
            // matrice de verification change.
            select {
                types.set(listOf(IntelliJPlatformType.PyCharmProfessional))
                channels.set(listOf(ProductRelease.Channel.EAP))
                sinceBuild.set("263.*")
                untilBuild.set("263.*")
            }
        }
    }
}

// Toolchain intentionally omitted: this build runs on JDK 21 (set via JAVA_HOME
// at the gradle wrapper invocation) which is supported by the IntelliJ Platform
// Gradle Plugin 2.7.2. Bytecode target is still Java 17 (see JavaCompile below).
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

// 07/11/2026 (cycle 149) — palier Pro n°1.
//
// Le plugin porte maintenant un `<product-descriptor code="PPCOCKPITPRO" .../>`
// (licence native JetBrains) : l'IDE le considère donc comme un plugin
// **payant**. La tâche `buildSearchableOptions` lance l'IDE en headless pour
// indexer les options de réglages — pour un plugin payant, l'IDE ouvre une
// boite de dialogue d'achat et la tâche ne peut pas aboutir.
//
// Message exact de la plateforme (07/11/2026, Gradle 8.10.2 + IntelliJ
// Platform Gradle Plugin 2.7.2, PyCharm 2024.3) :
//   "Due to IDE limitations, it is impossible to run the IDE in headless mode
//    to collect searchable options for a paid plugin.
//    As paid plugins require providing a valid license and presenting a UI
//    dialog, it is impossible to handle such a case, and the task will fail.
//    Please consider disabling the task."
//
// La doc officielle dit donc explicitement de désactiver la tâche :
//   https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html#intellijPlatform-buildSearchableOptions
// C'est ce qu'on fait ici. Les « searchable options » ne servent qu'à la
// recherche dans les réglages de l'IDE : le plugin n'a aucun réglage
// indexable (son panneau de réglages est vide côté index), donc la perte est
// nulle.
tasks.buildSearchableOptions {
    enabled = false
}

// La tache `jarSearchableOptions` emballe la sortie de la precedente dans un
// JAR `*-searchableOptions.jar` distribue avec le plugin. La source etant
// desactivee, ce JAR est vide : on le desactive aussi (le plugin n'a aucun
// reglage indexable), ce qui rend aussi le ZIP reproductible d'un build a
// l'autre (le cache Gradle peut sinon resservir unancien JAR de 750 octets
// a la place du JAR vide de 261 octets).
tasks.jarSearchableOptions {
    enabled = false
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-opt-in=kotlin.RequiresOptIn")
    }
}