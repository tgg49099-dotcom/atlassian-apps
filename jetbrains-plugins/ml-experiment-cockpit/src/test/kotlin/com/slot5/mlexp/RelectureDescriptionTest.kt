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

    /**
     * The trial length the Marketplace has settled for the Pro tier:
     * `purchaseInfo.trialPeriod` of the public API of the plugin 35416,
     * measured on 12/11/2026 = 30 days.  If the vendor changes the setting,
     * this constant and the texts that carry the announcement change together.
     */
    private val dureeReglee = 30

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
            "free trial"
        )) {
            assertTrue(pluginXml.contains(terme), "La description doit mentionner la limite/valeur : $terme")
        }
    }

    /**
     * 4 - La duree d'essai annoncee dans un texte public est la duree reglee.
     *
     * La duree reelle de l'essai est accordee par le Marketplace (reglage de
     * vente), pas par le code : la description livree annonce cette duree
     * (30 jours, `purchaseInfo.trialPeriod`), et c'est la seule qu'elle peut
     * annoncer.  Un texte public qui promettrait une autre duree serait faux
     * le jour ou le reglage change.
     */
    @Test
    @DisplayName("4 - Les textes publics n'annoncent que la duree reglee (30 jours)")
    fun dureeAnnonceeEgaleDureeReglee() {
        val pluginXml = lire("src/main/resources/META-INF/plugin.xml")
        val buildGradle = lire("build.gradle.kts")
        val panneau = lire("src/main/kotlin/com/slot5/mlexp/pro/PanneauComparaison.kt")
        val duree = Regex("""\b(\d+)\s*-?\s*days?\b""")

        for (nom in listOf("plugin.xml", "build.gradle.kts")) {
            val texte = if (nom == "plugin.xml") pluginXml else buildGradle
            for (trouve in duree.findAll(texte)) {
                val annoncee = trouve.groupValues[1].toInt()
                assertEquals(
                    dureeReglee,
                    annoncee,
                    "$nom annonce une duree d'essai de '$annoncee' jour(s) alors que la " +
                        "duree reglee est de $dureeReglee jours (purchaseInfo.trialPeriod)"
                )
            }
        }

        // le libelle de l'onglet verrouille : le prix et l'essai, sans duree
        assertTrue(
            panneau.contains("ML Experiment Cockpit Pro - \$29/year, free trial."),
            "Le libelle de l'onglet verrouille doit dire le prix et l'essai, sans duree"
        )
        val trouvePanneau = duree.find(panneau)
        assertTrue(
            trouvePanneau == null,
            "Le panneau ne doit afficher aucune duree (trouve : ${trouvePanneau?.value})"
        )
    }
}
