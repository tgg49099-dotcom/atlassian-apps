package com.slot5.mlexp

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **Relecture de l'anglais, du prix et des notes de version** — cycle 167.
 *
 * 3 demandes de la mere (11/11/2026), verifiees ici mecaniquement :
 *
 *   - A1  l'article anglais : plus aucun « a ML Experiment Cockpit Pro »
 *         (le bon article est « an », parce que « ML » se lit « em-el ») ;
 *   - A2  le prix : « $29/year » et « free trial » dans les **3 endroits**
 *         (le panneau Compare, le `plugin.xml`, le `build.gradle.kts`) ;
 *   - A3  l'essai : **aucune duree** annoncée dans les sources livrees
 *         (la duree de l'essai est accordee par le Marketplace, dans le
 *         reglage de vente : le code ne decide pas, le texte ne promet
 *         donc pas) ;
 *   - A4  les notes de version : une section « 2026.1.1 » propre, et le
 *         **meme texte** dans le `plugin.xml` et dans le `build.gradle.kts`
 *         (c'est le second qui ecrase le premier a la construction : c'est
 *         la source REELLE de ce qui est livre).
 *
 * Ces tests lisent les sources du depot : ils ne lancent rien, ils n'appellent
 * aucune API, ils ne creent aucun compte.
 */
class RelectureAnglaisPrixTest {

    private fun lire(chemin: String): String {
        val f = File(chemin)
        assertTrue(f.exists(), "the file $chemin must exist")
        return f.readText(Charsets.UTF_8)
    }

    /** The 3 places that carry the price of the Pro tier. */
    private val troisEndroits = listOf(
        "src/main/kotlin/com/slot5/mlexp/pro/PanneauComparaison.kt",
        "src/main/resources/META-INF/plugin.xml",
        "build.gradle.kts"
    )

    /**
     * Every source file that ends up in the delivered plugin.
     * Only the files that are present are checked: the delivery folder is
     * slimmed on purpose (CHANGELOG.md, docs/ and scripts/ live outside the
     * tree sent to the reviewer), so a missing file is not an error here.
     */
    private fun sourcesLivrees(): List<String> {
        val liste = mutableListOf<String>()
        File("src/main").walkTopDown().forEach { f ->
            if (f.isFile && (f.name.endsWith(".kt") || f.name.endsWith(".xml"))) {
                liste.add(f.path)
            }
        }
        for (nom in listOf("build.gradle.kts", "CHANGELOG.md", "README.md")) {
            if (File(nom).isFile) {
                liste.add(nom)
            }
        }
        return liste.sorted()
    }

    @Test
    @DisplayName("A1 - the English article is right: 'an ML Experiment Cockpit Pro', never 'a ML ...'")
    fun articleAnglaisCorrect() {
        val coupables = mutableListOf<String>()
        for (chemin in sourcesLivrees()) {
            val texte = lire(chemin)
            val m = Regex("\\ba ML Experiment Cockpit Pro").find(texte)
            if (m != null) {
                val ligne = texte.substring(0, m.range.first).count { it == '\n' } + 1
                coupables.add("$chemin:$ligne")
            }
        }
        assertTrue(
            coupables.isEmpty(),
            "'a ML Experiment Cockpit Pro' is wrong English (it must be 'an'): $coupables"
        )
    }

    @Test
    @DisplayName("A2 - the price and the free trial are written in the 3 places")
    fun prixDansLesTroisEndroits() {
        for (chemin in troisEndroits) {
            val texte = lire(chemin)
            assertTrue(
                texte.contains("\$29/year"),
                "$chemin must state the price as \$29/year"
            )
            assertTrue(
                texte.contains("free trial"),
                "$chemin must mention the free trial"
            )
        }
    }

    @Test
    @DisplayName("A3 - no trial duration is promised anywhere in the delivered sources")
    fun aucuneDureePromisee() {
        val coupables = mutableListOf<String>()
        val duree = Regex("""\b\d+\s*-?\s*days?\b""")
        for (chemin in sourcesLivrees()) {
            val texte = lire(chemin)
            val trouve = duree.find(texte)
            if (trouve != null) {
                coupables.add("$chemin (${trouve.value})")
            }
        }
        assertTrue(
            coupables.isEmpty(),
            "the trial length is granted by the Marketplace, not by the plugin: " +
                "no duration may be promised in $coupables"
        )
    }

    @Test
    @DisplayName("A4a - the release notes of the plugin.xml and of the build.gradle.kts are the same text")
    fun notesParitantes() {
        val px = lire("src/main/resources/META-INF/plugin.xml")
        val bg = lire("build.gradle.kts")
        val debutPx = "<change-notes><![CDATA["
        val debutBg = "changeNotes = \"\"\""
        val finBg = "\"\"\".trimIndent()"
        val a = px.substring(
            px.indexOf(debutPx) + debutPx.length,
            px.indexOf("]]></change-notes>", px.indexOf(debutPx))
        ).trim()
        val b = bg.substring(
            bg.indexOf(debutBg) + debutBg.length,
            bg.indexOf(finBg, bg.indexOf(debutBg))
        ).trim()
        assertEquals(a, b, "the two sources of the release notes must carry the same text")
    }

    @Test
    @DisplayName("A4b - the release notes describe the 2026.1.1")
    fun section2026Presente() {
        for (chemin in listOf(
            "src/main/resources/META-INF/plugin.xml",
            "build.gradle.kts"
        )) {
            val texte = lire(chemin)
            assertTrue(
                texte.contains("<h3>2026.1.1 "),
                "$chemin must describe the version 2026.1.1 in its release notes"
            )
            assertTrue(
                texte.contains("\$29/year") && texte.contains("free trial"),
                "$chemin must state the real price and the free trial in the 2026.1.1 notes"
            )
            assertTrue(
                texte.contains("pooled threads"),
                "$chemin must mention the switch to the platform's pooled threads"
            )
        }
    }

    @Test
    @DisplayName("A5 - the description and the release notes never promise more than the Pro tier does")
    fun pasDePromesseEnTrop() {
        val px = lire("src/main/resources/META-INF/plugin.xml")
        // The 100 000 points are a Pro-only limit: the free chart keeps 5 000.
        assertTrue(
            px.contains("5 000 points (free chart)") && px.contains("100 000 points (in the Compare view)"),
            "the table must keep the free limit and the Pro limit apart"
        )
        assertFalse(
            px.contains("unlimited"),
            "the plugin must never promise an unlimited number of points"
        )
    }
}
