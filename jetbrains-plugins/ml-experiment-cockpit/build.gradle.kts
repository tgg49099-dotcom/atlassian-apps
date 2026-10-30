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
        version = project.version.toString()
        ideaVersion {
            sinceBuild = (project.findProperty("pluginSinceBuild") as String?) ?: "243"
            untilBuild = (project.findProperty("pluginUntilBuild") as String?) ?: "263.*"
        }
        description = """
            <p>ML Experiment Cockpit — browse your MLflow experiments, runs, metrics and Model Registry models directly inside the JetBrains IDE.</p>
            <p>Connects to a running MLflow Tracking Server, an Optuna
            Dashboard and the W&amp;B GraphQL API, and surfaces experiment /
            run / study / trial / sweep / model-registry metadata directly
            inside the IDE. Built for ML engineers who already use MLflow,
            Optuna or W&amp;B and want the IDE to see their experiments and
            registered models instead of switching to a browser tab.</p>
            <ul>
                <li><b>MLflow</b>: browse experiments and runs, params, metrics and metric charts.</li>
                <li><b>MLflow Model Registry</b>: list registered models and their versions.</li>
                <li><b>Optuna</b>: browse studies and trials from an Optuna Dashboard.</li>
                <li><b>W&amp;B Sweeps</b>: list sweeps by state with their run count (read-only, W&amp;B API key).</li>
            </ul>
            <p>Open it from the <em>ML Experiment Cockpit</em> tool window; configure servers in
            <em>Settings | Tools | ML Experiment Cockpit</em>.</p>
        """.trimIndent()
        changeNotes = """
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
            </ul>
        """.trimIndent()
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
            select {
                types.set(listOf(IntelliJPlatformType.PyCharmProfessional))
                channels.set(listOf(ProductRelease.Channel.EAP))
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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-opt-in=kotlin.RequiresOptIn")
    }
}