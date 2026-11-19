package com.slot5.mlexp.pro

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **THE RELEASE NOTES OF 2026.1.9** — cycle 246 (consigne of the mother,
 * 19/11/2026).
 *
 * The only lever the mother has before the listing is frozen (28/11) is the
 * **release notes**: they must make a reader **TRY** the Pro tier. The
 * notes of 2026.1.9 must therefore say, in **one sentence**:
 *
 *   1. what the Pro tier brings: **compare 2 runs** and **export the
 *      comparison as one standalone HTML file** ;
 *   2. that the **trial is free for 30 days** (`purchaseInfo.trialPeriod`
 *      = 30, measured on the public Marketplace API on 19/11/2026).
 *
 * And they must **not** carry a « No functional change: » under a version
 * that adds a function (the Studio found the wording clumsy).
 *
 * The notes live in **two** sources — the `plugin.xml` and the
 * `build.gradle.kts` — and the second one overwrites the first at build
 * time: both must carry the same text (checked by `RelectureAnglaisPrixTest`
 * too). These tests check **both**.
 */
class NotesVersion2026109Test {

    /** The 2 sources that carry the release notes. */
    private val sources = listOf(
        "src/main/resources/META-INF/plugin.xml",
        "build.gradle.kts"
    )

    private fun lire(chemin: String): String {
        val f = File(chemin)
        assertTrue(f.exists(), "the file $chemin must exist")
        return f.readText(Charsets.UTF_8)
    }

    /** The block of the notes of 2026.1.9 (from its `h3` to the next one). */
    private fun bloc2026109(texte: String): String {
        val debut = texte.indexOf("<h3>2026.1.9 ")
        assertTrue(debut >= 0, "the release notes must describe the version 2026.1.9")
        val fin = texte.indexOf("<h3>", debut + 1)
        return texte.substring(debut, if (fin >= 0) fin else texte.length)
    }

    /**
     * The **first sentence** of the block.
     *
     * Two readings are possible — the title (`h3`), which is the first
     * thing a reader sees, or the first bullet. The block is written so
     * that the two carry the same message: the test checks **both**.
     */
    private fun premierePhraseDuTitre(bloc: String): String {
        val titre = bloc.substringAfter("<h3>").substringBefore("</h3>")
        // the version prefix ("2026.1.9 - ") is not part of the sentence
        val sansPrefixe = titre.substringAfter("- ", "").trim()
        return sansPrefixe.substringBefore(".").trim()
    }

    /** The first sentence of the first bullet of the block. */
    private fun premierePhraseDuPremierPuce(bloc: String): String {
        val puce = bloc.substringAfter("<li>").substringBefore("</li>")
        // the HTML tags are not read by the reader
        val sansTags = puce.replace(Regex("<[^>]*>"), "").trim()
        return sansTags.substringBefore(".").trim()
    }

    /** The phrase announces the 2 functions of the Pro tier. */
    private fun annonceLesFonctions(phrase: String): Boolean {
        val annonceCompare = phrase.contains("2 runs", ignoreCase = true)
        val annonceHtml = phrase.contains("HTML file", ignoreCase = true) &&
            (phrase.contains("standalone", ignoreCase = true) ||
                phrase.contains("self-contained", ignoreCase = true))
        return annonceCompare && annonceHtml
    }

    /** The phrase announces the free trial of 30 days. */
    private fun annonceEssai30Jours(phrase: String): Boolean {
        val duree = Regex("""\b30\s*-?\s*days?\b""").containsMatchIn(phrase)
        return duree && phrase.contains("free", ignoreCase = true)
    }

    @Test
    @DisplayName("N1 - the notes of 2026.1.9 are on top, in both sources, and the history is kept")
    fun lesNotesDe2026109SontEnTete() {
        for (chemin in sources) {
            val texte = lire(chemin)
            assertTrue(
                texte.contains("<h3>2026.1.9 "),
                "$chemin must describe the version 2026.1.9"
            )
            assertTrue(
                texte.indexOf("<h3>2026.1.9 ") < texte.indexOf("<h3>2026.1.8 "),
                "$chemin must describe the 2026.1.9 BEFORE the 2026.1.8 (top section)"
            )
            // the whole history is kept
            for (version in listOf("2026.1.8", "2026.1.7", "2026.1.6", "2026.1.5", "2026.1.4",
                "2026.1.3", "2026.1.2", "2026.1.1", "2026.1.0", "0.0.21", "0.0.20",
                "0.0.19", "0.0.18", "0.0.17")) {
                assertTrue(
                    texte.contains("<h3>$version "),
                    "$chemin must keep the history of the notes of $version"
                )
            }
        }
    }

    @Test
    @DisplayName("N2 - the first sentence says what the Pro tier brings: compare 2 runs + one standalone HTML file")
    fun laPremierePhraseAnnonceLesFonctions() {
        for (chemin in sources) {
            val bloc = bloc2026109(lire(chemin))
            val titre = premierePhraseDuTitre(bloc)
            val puce = premierePhraseDuPremierPuce(bloc)
            assertTrue(
                annonceLesFonctions(titre),
                "$chemin: the first sentence (the title) must announce the 2 functions " +
                    "of the Pro tier: compare 2 runs and export as one standalone HTML file - " +
                    "it says: $titre"
            )
            assertTrue(
                annonceLesFonctions(puce),
                "$chemin: the first sentence (the first bullet) must announce the 2 functions " +
                    "of the Pro tier - it says: $puce"
            )
        }
    }

    @Test
    @DisplayName("N3 - the first sentence says the trial is free for 30 days")
    fun laPremierePhraseAnnonceEssai30Jours() {
        for (chemin in sources) {
            val bloc = bloc2026109(lire(chemin))
            val titre = premierePhraseDuTitre(bloc)
            val puce = premierePhraseDuPremierPuce(bloc)
            assertTrue(
                annonceEssai30Jours(titre),
                "$chemin: the first sentence (the title) must announce the free 30-day " +
                    "trial - it says: $titre"
            )
            assertTrue(
                annonceEssai30Jours(puce),
                "$chemin: the first sentence (the first bullet) must announce the free " +
                    "30-day trial - it says: $puce"
            )
        }
    }

    @Test
    @DisplayName("N4 - the notes of 2026.1.9 carry no 'No functional change:'")
    fun lesNotesDe2026109NontPasDeNoFunctionalChange() {
        for (chemin in sources) {
            val bloc = bloc2026109(lire(chemin))
            assertFalse(
                bloc.contains("No functional change"),
                "$chemin: the notes of 2026.1.9 must not say 'No functional change' " +
                    "under a version that adds a function"
            )
            // and the wording of the 2026.1.8 block was corrected
            assertTrue(
                lire(chemin).contains("Nothing else changes: the four free tabs"),
                "$chemin: the notes of 2026.1.8 must say 'Nothing else changes:'"
            )
        }
    }

    @Test
    @DisplayName("N5 - the version of the plugin is 2026.1.9 everywhere")
    fun laVersionEst2026109() {
        val pluginXml = lire("src/main/resources/META-INF/plugin.xml")
        assertTrue(
            pluginXml.contains("<version>2026.1.9</version>"),
            "the plugin.xml must carry the version 2026.1.9"
        )
        val proprietes = lire("gradle.properties")
        assertTrue(
            proprietes.contains("pluginVersion = 2026.1.9"),
            "the ZIP of the delivery must be named 2026.1.9"
        )
        assertTrue(
            proprietes.contains("pluginMarketplaceVersion = 2026.1.9"),
            "the version shown on the Marketplace must be 2026.1.9"
        )
    }

    @Test
    @DisplayName("N6 - the 2 sources carry the same notes")
    fun lesDeuxSourcesPortentLesMemesNotes() {
        val px = lire("src/main/resources/META-INF/plugin.xml")
        val bg = lire("build.gradle.kts")
        val debutPx = "<change-notes><![CDATA["
        val debutBg = "changeNotes = \"\"\""
        val a = px.substring(
            px.indexOf(debutPx) + debutPx.length,
            px.indexOf("]]></change-notes>", px.indexOf(debutPx))
        ).trim()
        val b = bg.substring(
            bg.indexOf(debutBg) + debutBg.length,
            bg.indexOf("\"\"\".trimIndent()", bg.indexOf(debutBg))
        ).trim()
        assertEquals(a, b, "the 2 sources of the release notes must carry the same text")
    }
}
