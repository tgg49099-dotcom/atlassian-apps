package com.slot5.mlexp.pro

import com.slot5.mlexp.MetricPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **THE CONTROL OF THE RESOURCES, SHOWN TO FAIL** — cycle 246 (consigne of
 * the mother, 19/11/2026, retouche 2b du Studio).
 *
 * The control of proof 08 (the control of the resources of the exported
 * report) was **weak** in two ways:
 *
 *   1. it counted `http(s)://` in the **lower case only**, while the escape
 *      of the text neutralised the **lower case only**: an upper-case
 *      scheme (`HTTPS://`) therefore passed **both** — the proof could be
 *      green on a report that holds a URL;
 *   2. it did not require the `src` and the `href` of the report to start
 *      with `data:` — the real question.
 *
 * These tests exercise the control **against hostile texts**: a control
 * that is never shown to fail is not a control. They also check that the
 * **real** report passes it.
 */
class ControleRessourcesTest {

    // ------------------------------------------------------------------
    // 1. The control must catch what the first version missed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R1 - the control counts a scheme in ANY case: HTTPS:// is a URL like https://")
    fun leControleCompteLeSchemaDansToutesLesCasses() {
        for (hostile in listOf(
            "https://exemple.org/x",
            "http://exemple.org/x",
            "HTTPS://exemple.org/x",
            "Http://exemple.org/x",
            "HtTpS://exemple.org/x",
            "hTTp://exemple.org/x"
        )) {
            val html = "<td>$hostile</td>"
            assertTrue(
                ControleRessources.schemesExternes(html).isNotEmpty(),
                "the control must catch the scheme, whatever its case: $hostile"
            )
        }
        // and it stays quiet when there is no scheme at all
        assertTrue(
            ControleRessources.schemesExternes("<td>run-a</td>").isEmpty(),
            "the control must not invent a scheme"
        )
    }

    @Test
    @DisplayName("R2 - the control requires EVERY src/href to start with data:")
    fun leControleExigeDesRessourcesData() {
        val html = "<img src=\"https://exemple.org/courbe.png\">" +
            "<a href=\"/rapport.html\">report</a>" +
            "<img src='data:image/png;base64,AAAA'>"
        val ressources = ControleRessources.ressources(html)
        assertEquals(3, ressources.size, "the 3 resources must be found: $ressources")
        val nonData = ControleRessources.ressourcesNonData(html)
        assertEquals(2, nonData.size, "the 2 hostile resources must be caught: $nonData")
        assertTrue(nonData.any { it.startsWith("https://") }, "the external image must be caught: $nonData")
        assertTrue(nonData.any { it.startsWith("/rapport.html") }, "the relative link must be caught: $nonData")
        // a data: resource is accepted
        assertTrue(
            ControleRessources.ressourcesNonData("<img src=\"data:image/png;base64,AAAA\">").isEmpty(),
            "a data: resource is the only one allowed"
        )
    }

    @Test
    @DisplayName("R3 - the old, case-sensitive control would have missed the upper case")
    fun leControleAncienAuraitRateLeMaiuscule() {
        // the old control: a case-SENSITIVE count
        val ancien = Regex("https?://").findAll("<td>HTTPS://exemple.org/x</td>").count()
        assertEquals(0, ancien, "the old control really was blind to the upper case")
        // the new one sees it
        assertEquals(
            1,
            ControleRessources.schemesExternes("<td>HTTPS://exemple.org/x</td>").size,
            "the new control must see the upper case"
        )
    }

    // ------------------------------------------------------------------
    // 2. The escape of the text must neutralise the scheme in ANY case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R4 - the text of the server is escaped: the scheme is neutralised in ANY case")
    fun leSchemaEstNeutraliseDansToutesLesCasses() {
        for (hostile in listOf("https://exemple.org/x", "HTTPS://exemple.org/x", "Http://exemple.org/x")) {
            val echappe = ExportComparaison.echapper(hostile)
            assertFalse(
                Regex("(?i)https?://").containsMatchIn(echappe),
                "the scheme must be neutralised, whatever its case: $hostile -> $echappe"
            )
            // the text stays readable for the human (the report does not lie)
            assertTrue(echappe.contains("exemple.org/x"), "the value must stay readable: $echappe")
        }
    }

    // ------------------------------------------------------------------
    // 3. The REAL report passes the control
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R5 - the real report passes the control: no scheme, and every resource is a data:")
    fun leRapportReelPasseLeControle() {
        val html = ExportComparaison.html(comparaison(), pngBase64(), "loss", 1_762_000_000_000L)
        assertTrue(
            ControleRessources.schemesExternes(html).isEmpty(),
            "the report must hold no external scheme: ${ControleRessources.schemesExternes(html)}"
        )
        val ressources = ControleRessources.ressources(html)
        assertTrue(ressources.isNotEmpty(), "the report must hold at least one resource (the curve)")
        assertTrue(
            ControleRessources.ressourcesNonData(html).isEmpty(),
            "every resource of the report must be a data:: ${ControleRessources.ressourcesNonData(html)}"
        )
        assertTrue(ControleRessources.estAutonome(html), "the report must be self-contained")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A comparison built by hand (no server): the core of the report. */
    private fun comparaison(): ResultatComparaison.Comparaison {
        val a = RunRef(
            id = "a1", nom = "run-a", statut = "FINISHED",
            startTimeMs = 1_700_000_000_000L, endTimeMs = 1_700_000_060_000L,
            origine = "http://serveur:5000",
            params = mapOf("lr" to "0.01"),
            metriques = mapOf("loss" to 0.5, "acc" to 0.9)
        )
        val b = RunRef(
            id = "b1", nom = "run-b", statut = "FINISHED",
            startTimeMs = 1_700_000_000_000L, endTimeMs = 1_700_000_030_000L,
            origine = "http://serveur:5000",
            params = mapOf("lr" to "0.02"),
            metriques = mapOf("acc" to 0.8)
        )
        val r = ComparaisonRuns.calculer(listOf(a, b), ProState.PRO)
        assertTrue(r is ResultatComparaison.Comparaison, "the comparison must succeed: $r")
        return r as ResultatComparaison.Comparaison
    }

    /** The PNG of a 2-series curve, as the report carries it. */
    private fun pngBase64(): String = RenduCourbePng.base64(
        listOf(MetricPoint(0, 1.0, 0L), MetricPoint(1, 0.5, 1L)),
        listOf(MetricPoint(0, 0.8, 0L), MetricPoint(1, 0.6, 1L)),
        "loss"
    )
}
