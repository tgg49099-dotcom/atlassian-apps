import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.7.2"
    kotlin("jvm") version "1.9.25"
}

group = "com.tgglabs"
// Version unique -> gradle.properties : `pluginVersion`.
// 27/10/2026 : il n'y a PLUS de version en dur ici. Avant : "0.1.0" code en dur alors que
// gradle.properties disait 0.0.1, puis "0.2.0" en dur : le ZIP et la fiche du Vendor
// Center devaient etre d'accord, et ils ne l'etaient pas. La seule source de verite
// est desormais `pluginVersion` dans gradle.properties (le build echoue si elle manque).
version = project.findProperty("pluginVersion") as String

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA Community 2024.3 (build 243) : c'est la version que le slot 5
        // compile et publie avec succes (26/10/2026).
        create("IC", (project.findProperty("platformVersion") as String?) ?: "2024.3")
        // 27/10/2026 : le cadre de test de la plateforme (testFramework.jar de l'IDE
        // deja present dans le conteneur : aucun telechargement). Il permet d'ecrire
        // un test qui demande a l'action si elle est visible, comme le fait le
        // menu contextuel du Project view.
        testFramework(TestFrameworkType.Platform)
    }
    implementation(project(":core"))
    // POI est aussi compile ici : l'auto-test du plugin ecrit deux vrais .xlsx
    // (memes versions que le module core -> un seul jeu de jars dans la distribution).
    // 27/10/2026 : on garde le jar de schemas "lite" (poi-ooxml-lite) et non
    // "poi-ooxml-full" : le test du 27/10 (65 vrais .xlsx, memes resultats avec
    // les deux jar) ne prouve pas le besoin de "full", et "full" weighs 16,9 Mo
    // de plus dans le ZIP. Voir core/build.gradle.kts et
    // /work/evidence/POI-LITE-2710.txt.
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    // 27/10/2026 : `kotlin.stdlib.default.dependency=false` (gradle.properties) retire
    // le stdlib Kotlin de tous les modules. On le declare en `compileOnly` pour
    // compiler ; il n'est pas embarque dans le ZIP (l'IDE le fournit a l'execution).
    compileOnly(kotlin("stdlib"))

    // Les tests du module plugin : ils utilisent le cadre de test de la plateforme
    // (TestActionEvent, LightVirtualFile) pour verifier la regle de visibilite de
    // l'action. `kotlin("test")` ramene aussi le stdlib pour la compilation des tests.
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Le cadre de test de la plateforme enregistre un `LauncherSessionListener`
    // (ServiceLoader) qui a besoin de JUnit 4 sur le classpath, sinon le
    // lanceur de tests ne demarre pas :
    //   "Could not start Gradle Test Executor 1 ... NoClassDefFoundError:
    //    junit/framework/TestCase"
    testRuntimeOnly("junit:junit:4.13.2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "17" }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// 27/10/2026 : les tests du module plugin sont en JUnit 5 (Jupiter). Sans
// `useJUnitPlatform()`, Gradle utilise son runner JUnit 4 par defaut, ne trouve
// AUCUN test et affiche "0 tests, 0 failures" -> BUILD SUCCESSFUL sans avoir
// rien verifie (faux vert silencieux).
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.tgglabs.xlsxdiff"
        name = "XLSX Diff"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = (project.findProperty("pluginSinceBuild") as String?) ?: "243"
            untilBuild = (project.findProperty("pluginUntilBuild") as String?) ?: "263.*"
        }
        description = """
            <p>Compare two <code>.xlsx</code> spreadsheets inside your IDE and see exactly what changed,
            cell by cell: value, formula, rows added, rows removed, rows modified.</p>
            <p>Select <b>two</b> <code>.xlsx</code> files in the Project view, right-click and choose
            <b>Compare XLSX Files</b>: the report opens read-only in an editor tab.</p>
            <p>Save the report as a CSV file with <b>Export CSV…</b>, in the same menu or on the tab of
            the report. The two files are opened read-only, your files are never modified.</p>
        """.trimIndent()
        changeNotes = """
            <p>Cell-by-cell comparison of two .xlsx files, with CSV export.</p>
            <ul>
            <li>Select two <code>.xlsx</code> files in the Project view and choose <b>Compare XLSX Files</b>.</li>
            <li>The report opens read-only in an editor tab: every difference is listed with its sheet,
            cell, value and formula.</li>
            <li><b>Export CSV…</b> saves the report as a CSV file (UTF-8 with a byte order mark, and
            values that Excel would read as a formula are neutralised).</li>
            <li>Read-only: your files are never modified.</li>
            </ul>
        """.trimIndent()
        vendor {
            name = "Tgg Labs"
            // 27/10/2026 (mesure, mere) : l'adresse de contact dediee est
            // tgg.apps.support@gmail.com — c'est l'email que portent les 4 versions
            // publiees et approuvees de 35416, et l'API publique renvoie pour ce
            // plugin email = tgg.apps.support@gmail.com. La Marketplace accepte un
            // email de contact different de l'email du compte editeur.
            // L'adresse personnelle du compte ne doit figurer dans AUCUN fichier.
            email = "tgg.apps.support@gmail.com"
            url = "https://plugins.jetbrains.com/vendor/tgg-labs"
        }
    }
}
