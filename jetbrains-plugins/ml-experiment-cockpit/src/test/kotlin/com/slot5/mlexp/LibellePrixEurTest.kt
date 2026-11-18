package com.slot5.mlexp

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **Le libelle EUR du prix du palier Pro** — 2026.1.6, decision de la
 * Direction du 18/11/2026.
 *
 * Le palier Pro est vendu **29 EUR/an** : partout ou les sources livrees
 * ecrivaient « $29/year », elles doivent desormais ecrire
 * « 29 EUR/year, VAT depending on your country » (l'interface du plugin est
 * en anglais ; la TVA depend du pays de l'acheteur).
 *
 * Ce test est le **garde-fou mecanique** de cette decision : il **ECHOUE** si
 * la chaine « $29 » subsiste dans les sources livrees. Les fichiers
 * `.avant-20261118` (sauvegardes des fichiers avant edition) ne sont pas des
 * sources livrees : ils ne sont pas scannes — les 4 fichiers ci-dessous sont
 * exactement ceux qui portent le prix cote livrable.
 *
 * Le prix (29 EUR/an) et la duree de l'essai (30 jours) ne changent PAS :
 * ce test ne touche ni a l'un ni a l'autre, il ne verifie que le LIBELLE.
 */
class LibellePrixEurTest {

    /** The delivered sources that carry the price of the Pro tier. */
    private val sourcesLivrees = listOf(
        "src/main/resources/META-INF/plugin.xml",
        "src/main/kotlin/com/slot5/mlexp/pro/PanneauComparaison.kt",
        "build.gradle.kts",
        "README.md"
    )

    private fun lire(chemin: String): String {
        val f = File(chemin)
        assertTrue(f.exists(), "the file $chemin must exist")
        return f.readText(Charsets.UTF_8)
    }

    /**
     * The old dollar label must be GONE from every delivered source: the
     * tier is sold in euros (29 EUR/year) and the Marketplace sale settings
     * say EUR, so a "$29" left in a shipped text would contradict them.
     */
    @Test
    @DisplayName("EUR-1 - no '$29' left in the delivered sources (plugin.xml, panel, build.gradle.kts, README)")
    fun aucunDollar29Residuel() {
        val coupables = mutableListOf<String>()
        for (chemin in sourcesLivrees) {
            val texte = lire(chemin)
            val position = texte.indexOf("\$29")
            if (position >= 0) {
                val ligne = texte.substring(0, position).count { it == '\n' } + 1
                coupables.add("$chemin:$ligne")
            }
        }
        assertTrue(
            coupables.isEmpty(),
            "the Pro tier is sold in EUR: '\$29' must not appear in the delivered " +
                "sources (29 EUR/year, VAT depending on your country) - found: $coupables"
        )
    }

    /**
     * The EUR label itself must be present in every delivered source that
     * names the price, VAT mention included (an amount alone would hide
     * that the buyer's country changes the total).
     */
    @Test
    @DisplayName("EUR-2 - '29 EUR/year, VAT depending on your country' is written in every delivered source that names the price")
    fun libelleEurPresentPartout() {
        for (chemin in sourcesLivrees) {
            val texte = lire(chemin)
            assertTrue(
                texte.contains("29 EUR/year"),
                "$chemin names the price and must state it as 29 EUR/year"
            )
            assertTrue(
                texte.contains("VAT depending on your country"),
                "$chemin must add that the VAT depends on the country"
            )
        }
    }

    /**
     * The change of label must not change the deal: the trial length the
     * panel may announce is the settled one (30 days,
     * `purchaseInfo.trialPeriod`) and NO other. Since 2026.1.7 (consigne of
     * the mother, 19/11/2026) the locked card SELLS the tier and announces
     * the settled length ("30-day free trial"): that length is measured on
     * the public API of the plugin, so announcing it is honest - any other
     * duration would be a lie the day the setting moves.
     */
    @Test
    @DisplayName("EUR-3 - the panel announces the settled trial only: \"30-day\", never another duration")
    fun leLibelleNeChangePasLeMarche() {
        val panneau = lire("src/main/kotlin/com/slot5/mlexp/pro/PanneauComparaison.kt")
        assertFalse(
            panneau.contains("\$29"),
            "the panel must not carry the old dollar price"
        )
        val duree = Regex("""\b(\d+)\s*-?\s*days?\b""")
        val coupables = duree.findAll(panneau)
            .filter { it.groupValues[1].toInt() != 30 }
            .map { it.value }
            .toList()
        assertTrue(
            coupables.isEmpty(),
            "the only trial length the panel may announce is the settled one (30 days) - found: $coupables"
        )
    }
}
