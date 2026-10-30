import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

group = "com.tgglabs"
version = "0.0.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IDE dans le DSL 2.x : le plugin 2.x télécharge l'IDE dans ~/.gradle
        create("IC", "2024.3")
    }
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

intellijPlatform {
    // buildSearchableOptions forke une 2e JVM qui n'hérite pas de org.gradle.jvmargs :
    // avec le plafond de 1,5 Go du conteneur elle se fait tuer (code 137).
    buildSearchableOptions = false

    pluginConfiguration {
        name = "Commit History Search"
        version = project.version.toString()
        description = "Search for a text string inside the diffs of your Git commit history, " +
            "directly in the IDE. Equivalent of 'git log -S' and 'git log -G': the commits where " +
            "the string was added, removed or modified are listed, and the diff of the matching file " +
            "opens in the IDE diff viewer. The IDE searches commit messages and hashes natively; " +
            "this plugin searches what the commits actually changed. Regular expressions are " +
            "POSIX ERE, like 'git -G': use [0-9] and not \\d. Limit: the scan covers the " +
            "whole history of the current branch and skips merge commits; the result list shows at " +
            "most the 500 most recent matching commits. The search runs in the background and can " +
            "be cancelled."
        changeNotes = "0.0.2 - Fixes: the diff of a RENAMED file now opens with its real " +
            "'before' side (the old path of the file is used); the regular expressions are " +
            "POSIX ERE, like 'git -G' (\\d is converted to [0-9], and the constructs that POSIX " +
            "ERE cannot do are now warned about in the window). 0.0.1 - First release: search a " +
            "string in the diffs of your Git history (merge commits are skipped), list the matching " +
            "commits and files, and open the diff of a result in the IDE."
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "263.*"
        }
    }
}

tasks.test {
    // les tests du cœur ne dépendent pas de l'IDE : pas de test framework IntelliJ à télécharger
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    maxHeapSize = "256m"
    // Les tests creent des fichiers dont le nom n'est pas ASCII : la JVM doit encoder
    // les noms de fichiers ET les arguments de la ligne de commande en UTF-8. Sans cela
    // le nom devient "caf?.txt" dans un conteneur en locale POSIX, et le defaut du nom
    // non ASCII ne peut pas etre reproduit du tout.
    environment("LANG", "C.UTF-8")
    environment("LC_ALL", "C.UTF-8")
}
