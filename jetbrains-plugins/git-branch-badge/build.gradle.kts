// Git Branch Badge — build script.
//
// Slot 5 (JetBrains Marketplace). Produit gratuit, UNE seule fonction :
// afficher la branche Git courante sur la racine du projet dans le panneau
// « Project » (YouTrack IJPL-73102, 281 votes, ouvert depuis 2011).
//
// Meme pile que le plugin 35416 deja publie (IntelliJ Platform Gradle Plugin
// 2.7.2 + Kotlin 1.9.25 + JDK 21), donc la recette est deja validee.
//
// References :
//   - https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
//   - EP utilisee : com.intellij.projectViewNodeDecorator
//     (declare dans LangExtensionPoints.xml de l'IDE, presente en 2024.3 ET 2026.2,
//      signature identique : decorate(ProjectViewNode<?>, PresentationData))

import java.net.URLClassLoader
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.7.2"
    kotlin("jvm") version "1.9.25"
}

group = "com.tgglabs.branchbadge"
version = (project.findProperty("pluginVersion") as String?) ?: "0.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // PyCharm Professional 2024.3 : deja dans le cache Gradle du conteneur,
        // le build ne telecharge rien.
        create("PY", (project.findProperty("platformVersion") as String?) ?: "2024.3")
        // Plugin Git de l'IDE : BUNDLE, donc fourni par l'IDE et non inclus
        // dans le ZIP du plugin. Il ne sert qu'au listener de rafraichissement
        // (dependance OPTIONNELLE dans plugin.xml) : la fonction principale lit
        // `.git/HEAD` et fonctionne meme si l'utilisateur desactive Git.
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.tgglabs.branchbadge"
        name = (project.findProperty("pluginName") as String?) ?: "Git Branch Badge"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = (project.findProperty("pluginSinceBuild") as String?) ?: "243"
            untilBuild = (project.findProperty("pluginUntilBuild") as String?) ?: "263.*"
        }
        description = """
<p>Shows the current Git branch of your project right in the Project view.</p>
<ul>
<li><b>Always visible</b> — the project root shows <code>path [branch]</code>: the branch is right where you look for it, and the project path is still there.</li>
<li><b>Always up to date</b> — the label refreshes by itself the moment you switch branches, from the IDE or from a terminal.</li>
<li><b>Detached HEAD too</b> — on a detached HEAD the label shows <code>detached @ abc1234</code> instead of disappearing.</li>
<li><b>No configuration</b> — nothing to set up, no account, no network call: the branch is read from the local <code>.git/HEAD</code> file, in IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion and every other JetBrains IDE.</li>
</ul>
        """.trimIndent()
        // NOTE (cycle 70) : `changeNotes` n'est PLUS declare ici. Le bloc
        // `pluginConfiguration` du plugin Gradle 2.7.2 REECRIT l'element
        // <change-notes> du source quand cette propriete est presente, et la
        // laisse intact quand elle est absente (verifie sur le bytecode de
        // PatchPluginXmlTask : le 6e parametre booleen vaut `false` pour
        // change-notes, donc l'element source n'est pas supprime).
        // Les notes de version sont donc ecrites UNE seule fois, dans
        // src/main/resources/META-INF/plugin.xml, ou la revue Marketplace les
        // lit. Les deux ne peuvent plus diverger.
        vendor {
            name = (project.findProperty("pluginVendorName") as String?) ?: "Tgg Labs"
            email = (project.findProperty("pluginVendorEmail") as String?) ?: "tgg.apps.support@gmail.com"
            url = (project.findProperty("pluginVendorUrl") as String?) ?: "https://plugins.jetbrains.com/vendor/tgg-labs"
        }
    }
    // Aucune publication depuis ce build : le jeton n'est meme pas lu ici.
    // `publishPlugin` / `uploadPlugin` ne doivent JAMAIS etre lances sans
    // l'accord du proprietaire (cf. NOTES.md).
    publishing {
        token = System.getenv("JETBRAINS_MARKETPLACE_TOKEN")
    }

    // Verification de compatibilite binaire (Plugin Verifier de JetBrains).
    // `ide(...)` liste un IDE a verifier ; on reprend le meme IDE que la
    // compilation (PyCharm Professional 2024.3, deja en cache) -> aucun
    // telechargement, verification reproductible hors ligne.
    // Pieges vus ce jour :
    //  - `local(...)` entre en conflit avec la dependance de compilation :
    //    "The 'intellijPlatformDependency' configuration already contains the
    //     following IntelliJ Platform dependency: PY-2024.3 (installer)" ;
    //  - `current()` n'existe PAS dans la version 2.7.2 du plugin Gradle
    //    ("Unresolved reference: current") ;
    //  - sans liste d'IDEs : "No IDE resolved for verification with the
    //    IntelliJ Plugin Verifier."
    pluginVerification {
        ides {
            // `ide(...)` est DEPRECATED en 2.7.2 : forme recommandee = create(...).
            // On verifie contre PyCharm Professional 2024.3, l'IDE de compilation :
            // c'est la borne basse de la plage 243..263.*, donc la verification la
            // plus severe (toute API utilisee doit exister dans TOUTE la plage).
            create(IntelliJPlatformType.PyCharmProfessional, "2024.3") {
                useInstaller.set(true)
            }
        }
    }
}

// Bytecode Java 17, comme le plugin deja publie.
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

// =============================================================================
// TEST DE BUILD : le descripteur optionnel `META-INF/git4idea.xml`
// =============================================================================
// POURQUOI : la revue du 27/10 a montre que le descripteur portait un `topic`
// invalide — `git4idea.repo.GitRepository.GIT_REPO_CHANGE`, qui est le nom d'un
// CHAMP et pas celui d'une CLASSE. La plateforme ne pouvait donc pas resoudre
// le topic, le listener n'etait JAMAIS enregistre, et le rafraichissement ne
// provenait que du sondage de 2 s. Rien ne le detectait : aucun test ne lisait
// le descripteur.
//
// CE QUE FAIT CE CONTROLE (a chaque build) :
//  1. il LIT `src/main/resources/META-INF/git4idea.xml` sur le disque ;
//  2. il verifie que la valeur de `topic` designe une classe EXISTANTE
//     (`Class.forName`) sur le classpath de compilation — c'est exactement le
//     critere du defaut ;
//  3. il verifie que cette classe est une interface (un topic en est une) ;
//  4. il verifie que la classe du listener existe et IMPLEMENTE le topic ;
//  5. il verifie que `plugin.xml` ENREGISTRE le descripteur
//     (`<depends optional="true" config-file="git4idea.xml">Git4Idea</depends>`),
//     sans quoi le fichier ne serait jamais charge par la plateforme.
//
// Un controle qui ne peut pas echouer ne prouve rien : ce controle a ete joue
// en NEGATIF (descripteur sabotage => build en echec), cf. le rapport du cycle.
// =============================================================================
val mainSourceSet = sourceSets["main"]
val descriptorFile = file("src/main/resources/META-INF/git4idea.xml")
val pluginXmlFile = file("src/main/resources/META-INF/plugin.xml")

val verifyPluginDescriptor by tasks.registering {
    group = "verification"
    description = "Verifie le descripteur optionnel git4idea.xml (topic = classe reelle, listener enregistre)"

    inputs.file(descriptorFile)
    inputs.file(pluginXmlFile)
    inputs.files(mainSourceSet.output)
    inputs.files(mainSourceSet.compileClasspath)

    doLast {
        val xml = descriptorFile.readText()
        val topicMatch = Regex("""topic\s*=\s*"([^"]+)"""").find(xml)
        val classMatch = Regex("""class\s*=\s*"([^"]+)"""").find(xml)
        if (topicMatch == null || classMatch == null) {
            throw GradleException(
                "ECHEC verifyPluginDescriptor : $descriptorFile ne declare pas un <listener class=... topic=...>"
            )
        }
        val topicName = topicMatch.groupValues[1]
        val listenerName = classMatch.groupValues[1]

        val urls = (mainSourceSet.compileClasspath.files + mainSourceSet.output.files)
            .filter { it.exists() }
            .map { it.toURI().toURL() }
        val loader = URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
        try {
            val topicClass = try {
                Class.forName(topicName, false, loader)
            } catch (e: ClassNotFoundException) {
                throw GradleException(
                    "ECHEC verifyPluginDescriptor : le topic « $topicName » n'est pas une classe " +
                        "(message exact : ${e.message}). Le listener ne peut donc pas etre enregistre."
                )
            }
            if (!topicClass.isInterface) {
                throw GradleException(
                    "ECHEC verifyPluginDescriptor : le topic « $topicName » n'est pas une interface."
                )
            }
            val listenerClass = try {
                Class.forName(listenerName, false, loader)
            } catch (e: ClassNotFoundException) {
                throw GradleException(
                    "ECHEC verifyPluginDescriptor : la classe du listener « $listenerName » n'existe pas " +
                        "(message exact : ${e.message})."
                )
            }
            if (!topicClass.isAssignableFrom(listenerClass)) {
                throw GradleException(
                    "ECHEC verifyPluginDescriptor : « $listenerName » n'implemente pas « $topicName »."
                )
            }

            val pluginXml = pluginXmlFile.readText()
            val registered = Regex(
                """<depends\s+optional="true"\s+config-file="git4idea\.xml"\s*>\s*Git4Idea\s*</depends>"""
            ).containsMatchIn(pluginXml)
            if (!registered) {
                throw GradleException(
                    "ECHEC verifyPluginDescriptor : $pluginXmlFile n'enregistre pas le descripteur " +
                        "(<depends optional=\"true\" config-file=\"git4idea.xml\">Git4Idea</depends>)."
                )
            }

            logger.lifecycle(
                "verifyPluginDescriptor : OK — topic « $topicName » (interface, classe existante), " +
                    "listener « $listenerName » enregistre, descripteur reference par plugin.xml."
            )
        } finally {
            loader.close()
        }
    }
}

// Le controle du descripteur fait partie de la fabrication du ZIP : un ZIP
// livre avec un descripteur invalide ne doit pas pouvoir exister.
tasks.matching { it.name == "buildPlugin" }.configureEach {
    dependsOn(verifyPluginDescriptor)
}

// =============================================================================
// TACHE DESACTIVEE : buildSearchableOptions
// =============================================================================
// POURQUOI : cette tache lance un IDE complet en arriere-plan pour indexer les
// pages de REGLAGES du plugin. Ce plugin n'a AUCUNE page de reglages (il n'y a
// rien a regler) : l'index produit serait donc vide. Elle a aussi TUE le build
// du cycle 71 :
//     Execution failed for task ':buildSearchableOptions'.
//     > Process 'command '.../jbr/bin/java'' finished with non-zero exit value 137
// 137 = SIGKILL du tueur OOM : le conteneur est limite a 1,5 Go de RAM, le
// daemon Gradle en prend deja ~900 Mo et l'IDE en arriere-plan demandait le
// reste. Desactiver la tache evite ce depassement et rend le build
// reproductible dans le conteneur.
tasks.matching { it.name == "buildSearchableOptions" }.configureEach {
    enabled = false
}
