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
 *   - A3  l'essai : la duree annoncee dans les sources livrees est
 *         **exactement celle que le Marketplace a reglee** (30 jours,
 *         `purchaseInfo.trialPeriod`) ; aucune autre duree ne peut etre
 *         promisee, pour ne pas pouvoir mentir si le reglage change ;
 *   - A4  les notes de version : une section « 2026.1.2 » propre, et le
 *         **meme texte** dans le `plugin.xml` et dans le `build.gradle.kts`
 *         (c'est le second qui ecrase le premier a la construction : c'est
 *         la source REELLE de ce qui est livre).
 *
 * Ces tests lisent les sources du depot : ils ne lancent rien, ils n'appellent
 * aucune API, ils ne creent aucun compte.
 */
class RelectureAnglaisPrixTest {

    /**
     * The trial length the Marketplace has settled for the Pro tier:
     * `purchaseInfo.trialPeriod` of the public API of the plugin 35416,
     * measured on 12/11/2026 = 30 days.  The delivered version announces that
     * length, and only that one.  If the vendor changes the setting, this
     * constant and the texts that carry the announcement change together.
     */
    private val dureeReglee = 30

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

    /**
     * The trial length is settled by the Marketplace (`purchaseInfo.trialPeriod`),
     * not by the code.  The delivered version announces the settled length, so
     * a source may promise that one and no other: a public text promising a
     * length of its own would be wrong the day the setting moves.
     */
    @Test
    @DisplayName("A3 - the only trial length the delivered sources may promise is the settled one (30 days)")
    fun dureeAnnonceeEgaleDureeReglee() {
        val coupables = mutableListOf<String>()
        val duree = Regex("""\b(\d+)\s*-?\s*days?\b""")
        for (chemin in sourcesLivrees()) {
            val texte = lire(chemin)
            for (trouve in duree.findAll(texte)) {
                val annoncee = trouve.groupValues[1].toInt()
                if (annoncee != dureeReglee) {
                    coupables.add(
                        "$chemin: '$annoncee day(s)' announced, $dureeReglee days settled"
                    )
                }
            }
        }
        assertTrue(
            coupables.isEmpty(),
            "the trial length is settled by the Marketplace ($dureeReglee days, " +
                "purchaseInfo.trialPeriod): the announced length must be the settled " +
                "one - $coupables"
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
    @DisplayName("A4b - the release notes describe the 2026.1.2")
    fun section2026Presente() {
        for (chemin in listOf(
            "src/main/resources/META-INF/plugin.xml",
            "build.gradle.kts"
        )) {
            val texte = lire(chemin)
            assertTrue(
                texte.contains("<h3>2026.1.2 "),
                "$chemin must describe the version 2026.1.2 in its release notes"
            )
            assertTrue(
                texte.contains("\$29/year") && texte.contains("free trial"),
                "$chemin must state the real price and the free trial in the 2026.1.2 notes"
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
