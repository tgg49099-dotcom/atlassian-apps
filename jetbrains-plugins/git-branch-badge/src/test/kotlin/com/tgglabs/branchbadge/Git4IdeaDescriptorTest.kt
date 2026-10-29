package com.tgglabs.branchbadge

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Test du DESCRIPTEUR optionnel `META-INF/git4idea.xml`.
 *
 * POURQUOI CE TEST EXISTE (demande explicite de la revue du 27/10) :
 * jusqu'ici, RIEN ne testait le descripteur. Le defaut qu'il contenait —
 * `topic="git4idea.repo.GitRepository.GIT_REPO_CHANGE"`, qui est le nom d'un
 * CHAMP et pas celui d'une classe — est donc passe inapercu : la plateforme
 * ne pouvait pas resoudre la classe du topic, le listener n'etait jamais
 * enregistre, et le rafraichissement ne provenait que du sondage de 2 s.
 *
 * CE QUE LE TEST FAIT :
 *  1. il LIT le descripteur sur le disque (le SOURCE, pas une copie en memoire) ;
 *  2. il verifie que la valeur de `topic` designe une CLASSE EXISTANTE
 *     (`Class.forName`) — c'est le critere exact du defaut ;
 *  3. il verifie que cette classe est une interface (un topic est une interface) ;
 *  4. il verifie que la classe du listener existe et IMPLEMENTE le topic ;
 *  5. il verifie que le descripteur est bien ENREGISTRE : `plugin.xml` doit
 *     declarer `<depends optional="true" config-file="git4idea.xml">Git4Idea</depends>`.
 *     Sans cela, le fichier serait un mort-né : jamais charge par la plateforme.
 *
 * Le test est joue HORS IDE (JUnit pur) : il lui faut la classe
 * `git4idea.repo.GitRepositoryChangeListener` sur le classpath, c'est-a-dire
 * le plugin Git de l'IDE — exactement ce que la plateforme fait au chargement.
 */
class Git4IdeaDescriptorTest {

    /** La racine du projet : on la cherche en remontant depuis le repertoire courant. */
    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "build.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "racine du projet introuvable (aucun build.gradle.kts au-dessus de ${System.getProperty("user.dir")})"
        )
    }

    private fun descriptorText(): String {
        val f = File(projectRoot(), "src/main/resources/META-INF/git4idea.xml")
        assertTrue(f.isFile, "descripteur introuvable : ${f.absolutePath}")
        return f.readText()
    }

    private fun pluginXmlText(): String {
        val f = File(projectRoot(), "src/main/resources/META-INF/plugin.xml")
        assertTrue(f.isFile, "plugin.xml introuvable : ${f.absolutePath}")
        return f.readText()
    }

    /** La valeur de l'attribut `topic` du premier `<listener>`. */
    private fun topicAttribute(): String {
        val text = descriptorText()
        val m = Regex("""topic\s*=\s*"([^"]+)"""").find(text)
        assertNotNull(m, "aucun attribut topic dans le descripteur")
        return m!!.groupValues[1]
    }

    /** La valeur de l'attribut `class` du premier `<listener>`. */
    private fun listenerClassAttribute(): String {
        val text = descriptorText()
        val m = Regex("""class\s*=\s*"([^"]+)"""").find(text)
        assertNotNull(m, "aucun attribut class dans le descripteur")
        return m!!.groupValues[1]
    }

    @Test
    @DisplayName("le topic du descripteur designe une classe EXISTANTE (pas un nom de champ)")
    fun topicIsAnExistingClass() {
        val topic = topicAttribute()
        val clazz = try {
            Class.forName(topic)
        } catch (e: ClassNotFoundException) {
            throw AssertionError("le topic « $topic » n'est pas une classe : le listener ne peut pas etre enregistre. " +
                "message exact : ${e.message}")
        }
        assertNotNull(clazz)
    }

    @Test
    @DisplayName("le topic du descripteur est une interface")
    fun topicIsAnInterface() {
        val topic = topicAttribute()
        val clazz = try {
            Class.forName(topic)
        } catch (e: ClassNotFoundException) {
            throw AssertionError("le topic « $topic » n'est pas une classe : ${e.message}")
        }
        assertTrue(clazz.isInterface, "le topic « $topic » n'est pas une interface")
    }

    @Test
    @DisplayName("le topic est bien l'interface du plugin Git de l'IDE")
    fun topicIsTheGitRepositoryChangeListener() {
        assertEquals("git4idea.repo.GitRepositoryChangeListener", topicAttribute())
    }

    @Test
    @DisplayName("la classe du listener existe et implemente le topic")
    fun listenerImplementsTheTopic() {
        val topicName = topicAttribute()
        val listenerName = listenerClassAttribute()
        val topic = try {
            Class.forName(topicName)
        } catch (e: ClassNotFoundException) {
            throw AssertionError("le topic « $topicName » n'est pas une classe : ${e.message}")
        }
        val listener = try {
            Class.forName(listenerName)
        } catch (e: ClassNotFoundException) {
            throw AssertionError("la classe du listener « $listenerName » n'existe pas : ${e.message}")
        }
        assertTrue(
            topic.isAssignableFrom(listener),
            "« $listenerName » n'implemente pas « $topicName »"
        )
    }

    @Test
    @DisplayName("le descripteur est ENREGISTRE par plugin.xml (dependance optionnelle)")
    fun descriptorIsRegisteredByPluginXml() {
        val xml = pluginXmlText()
        assertTrue(
            xml.contains("""config-file="git4idea.xml""""),
            "plugin.xml ne declare pas config-file=\"git4idea.xml\" : le descripteur ne sera jamais charge"
        )
        assertTrue(
            Regex("""<depends\s+optional="true"\s+config-file="git4idea\.xml"\s*>\s*Git4Idea\s*</depends>""")
                .containsMatchIn(xml),
            "plugin.xml ne declare pas la dependance optionnelle Git4Idea vers git4idea.xml"
        )
    }
}
