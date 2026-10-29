plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
}

repositories {
    mavenCentral()
}

dependencies {
    // 27/10/2026 : `poi-ooxml` (et son jar de schemas "lite") au lieu de
    // `poi-ooxml-full` (16,9 Mo de schemas en plus dans le ZIP).
    // Le jury demandait ce retour au "lite" "sauf si un test prouve qu'un
    // classeur reel a besoin du schema complet". Ce test a ete fait le
    // 27/10/2026 (preuves : /work/evidence/POI-FULL-2710.txt et
    // /work/evidence/POI-LITE-2710.txt) :
    //   - 65 vrais .xlsx de POI (test-data public : graphiques, tableaux,
    //     commentaires VML, mise en forme conditionnelle, validations) ;
    //   - lus avec les schemas "lite" ET avec les schemas "full" ;
    //   - resultat IDENTIQUE dans les deux cas (64 lus, 1 refuse dans les
    //     deux cas : un ZIP dont deux entrees ont le meme nom).
    // Donc aucun test ne prouve le besoin de "full" : on garde "lite".
    implementation("org.apache.poi:poi-ooxml:5.4.1")

    // 27/10/2026 : `kotlin.stdlib.default.dependency=false` (voir gradle.properties)
    // retire le stdlib Kotlin de TOUS les modules. Le module core n'a plus de
    // stdlib sur son classpath de compilation : on le declare explicitement, mais
    // seulement pour compiler (`compileOnly`) -> il n'est pas embarque dans le ZIP
    // (l'IDE le fournit a l'execution).
    compileOnly(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "17" }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// 27/10/2026 : le jar du module core est publie dans le lib/ du plugin.
// Il s'appelait "core.jar" : un nom qui ne dit rien. Il s'appelle maintenant
// "xlsx-diff-core-<version>.jar", comme les autres dependances du ZIP.
base {
    archivesName.set("xlsx-diff-core")
}

// RESTAURE le 26/10/2026 : la "recette 262" (IPGP 2.7.2 / Kotlin 1.9.25) avait SUPPRIME ce
// bloc. Sans `useJUnitPlatform()`, Gradle utilise son runner JUnit 4 par defaut, ne trouve
// AUCUN test (les tests sont en JUnit 5 / Jupiter) et affiche "0 tests, 0 failures" ->
// BUILD SUCCESSFUL sans avoir rien verifie. Faux vert silencieux : le build3 (-x test) de la
// mere est passe sans que personne ne le voie.
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
