package com.slot5.mlexp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Relecture hostile automatisée de la description publiée (plugin.xml et build.gradle.kts).
 * Vérifie l'intégrité de la description et documente l'anomalie bloquante constatée.
 */
class RelectureDescriptionTest {

    private fun lire(chemin: String): String {
        val f = File(chemin)
        assertTrue(f.exists(), "Le fichier $chemin doit exister")
        return f.readText(Charsets.UTF_8)
    }

    @Test
    @DisplayName("1 - Parité stricte entre plugin.xml et build.gradle.kts sur la description")
    fun testPariteDescription() {
        val pluginXml = lire("src/main/resources/META-INF/plugin.xml")
        val buildGradle = lire("build.gradle.kts")

        val debutXml = pluginXml.indexOf("<description><![CDATA[") + "<description><![CDATA[".length
        val finXml = pluginXml.indexOf("]]></description>")
        val descXml = pluginXml.substring(debutXml, finXml).trim()

        assertTrue(buildGradle.contains(descXml), "build.gradle.kts doit contenir la description mot pour mot")
    }

    @Test
    @DisplayName("2 - Le nombre d'onglets annoncé dans la description correspond aux 5 onglets réels")
    fun testIncoherenceNombreOnglets() {
        val pluginXml = lire("src/main/resources/META-INF/plugin.xml")
        val toolWindowFactory = lire("src/main/kotlin/com/slot5/mlexp/MlflowToolWindowFactory.kt")

        // La factory instancie 5 onglets inconditionnellement
        val occurrencesAddContent = Regex("contentManager\\.addContent").findAll(toolWindowFactory).count()
        assertEquals(5, occurrencesAddContent, "MlflowToolWindowFactory instancie exactement 5 onglets")

        // La description NE DOIT PLUS annoncer 'All four tabs' : la phrase est fausse (5 onglets reels)
        val phraseContradictoire = "All four tabs live in one"
        assertFalse(
            pluginXml.contains(phraseContradictoire),
            "La description ne doit pas contenir la phrase fausse '$phraseContradictoire'"
        )

        // La description DOIT annoncer les 5 onglets, nommes
        val phraseJuste = "All five tabs (Experiments, Optuna, Models, Sweeps and the Compare view) live in one"
        assertTrue(
            pluginXml.contains(phraseJuste),
            "La description doit contenir la phrase exacte : '$phraseJuste'"
        )
    }

    @Test
    @DisplayName("3 - Vérification des limites chiffrées dans la description")
    fun testLimitesChiffrees() {
        val pluginXml = lire("src/main/resources/META-INF/plugin.xml")
        for (terme in listOf(
            "500 experiments",
            "100 runs per experiment",
            "500 registered models",
            "500 model versions",
            "5 000 metric points per run",
            "100 000 points",
            "29/year",
            "14-day trial"
        )) {
            assertTrue(pluginXml.contains(terme), "La description doit mentionner la limite/valeur : $terme")
        }
    }
}
