package com.slot5.mlexp.pro

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests of the **Pro gate** of tier 1.
 *
 * v0.0.22. The gate is tested **without an IDE**: it only knows the shape of
 * the platform answer (`LectureLicence`), never the IntelliJ class. The 4
 * tests of the plan (`/work/cockpit-pro-n1-plan.md` section 4):
 *   1. without a licence -> the comparison is blocked;
 *   2. with a licence -> the comparison passes;
 *   3. expired licence -> the comparison is blocked, with the exact reason;
 *  12. the product code is written in one single place and it is consistent
 *      with the `plugin.xml` descriptor.
 */
class ProGateTest {

    // ------------------------------------------------------------------
    // Test 1 - without a licence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1 - without a licence: the comparison is blocked, with a reason")
    fun sansLicence_laComparaisonEstBloquee() {
        val etat = ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = null))
        assertEquals(ProState.ABSENT, etat, "no stamp = no licence")

        val r = ComparaisonRuns.calculer(deuxRuns(), etat)
        assertTrue(r is ResultatComparaison.Refus, "without a licence, nothing must be computed: $r")
        val refus = r as ResultatComparaison.Refus
        assertEquals(CodeRefus.BLOQUE_SANS_LICENCE, refus.code)
        assertTrue(refus.message.isNotBlank(), "the refusal must carry a readable reason")
    }

    // ------------------------------------------------------------------
    // Test 2 - with a licence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2 - with a licence: the comparison passes (0 gap on 2 identical runs)")
    fun avecLicence_laComparaisonPasse() {
        val etat = ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "key:une-cle"))
        assertEquals(ProState.PRO, etat)

        val r = ComparaisonRuns.calculer(deuxRuns(), etat)
        assertTrue(r is ResultatComparaison.Comparaison, "with a licence, the comparison must succeed: $r")
        assertEquals(0, (r as ResultatComparaison.Comparaison).nombreEcarts)
    }

    // ------------------------------------------------------------------
    // Test 3 - expired licence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3 - trial ended: the comparison is blocked with the exact reason")
    fun licenceExpiree_laComparaisonEstBloquee() {
        // A trial stamp carries the end date (in milliseconds): here, a date
        // in the past = trial ended.
        val sceau = "eval:" + (System.currentTimeMillis() - 60_000L)
        val etat = ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = sceau))
        assertEquals(ProState.EXPIRE, etat)

        val r = ComparaisonRuns.calculer(deuxRuns(), etat)
        assertTrue(r is ResultatComparaison.Refus, "an ended trial must block: $r")
        val refus = r as ResultatComparaison.Refus
        assertEquals(CodeRefus.LICENCE_EXPIREE, refus.code)
        assertTrue(
            refus.message.contains("trial"),
            "the reason must name the trial, it says: ${refus.message}"
        )
    }

    // ------------------------------------------------------------------
    // Test 3 bis - the other states
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3 bis - trial in progress, facade not initialised, unknown prefix")
    fun lesAutresEtatsDeLaPorte() {
        val futur = System.currentTimeMillis() + 86_400_000L
        assertEquals(
            ProState.ESSAI,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:$futur")),
            "a trial stamp that has not expired = trial in progress"
        )
        assertEquals(
            ProState.INCONNU,
            ProGate.etat(LectureLicence(facadeInitialisee = false)),
            "facade not initialised = we cannot conclude"
        )
        assertEquals(
            ProState.ABSENT,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "quelque-chose:autre")),
            "an unknown prefix is not a licence"
        )
        assertEquals(
            ProState.PRO,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "stamp:serveur-flottant")),
            "a floating server stamp = licence"
        )
        assertTrue(ProState.ESSAI.autorise(), "the trial authorises the comparison")
        assertTrue(!ProState.INCONNU.autorise(), "the unknown state opens nothing")
    }

    // ------------------------------------------------------------------
    // Test 3 ter - the unit of the trial stamp timestamp
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3 ter - the trial is read in milliseconds AND in seconds (the unit is not documented)")
    fun essaiSceauLuDansLesDeuxUnites() {
        val maintenant = System.currentTimeMillis()
        val dansUneSemaineMs = maintenant + 7L * 86_400_000L
        val dansUneSemaineSec = dansUneSemaineMs / 1000L
        val ilYAUneSemaineMs = maintenant - 7L * 86_400_000L
        val ilYAUneSemaineSec = ilYAUneSemaineMs / 1000L

        // A trial that has not expired = ESSAI, whatever the unit.
        assertEquals(
            ProState.ESSAI,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:$dansUneSemaineMs")),
            "trial in milliseconds = trial in progress"
        )
        assertEquals(
            ProState.ESSAI,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:$dansUneSemaineSec")),
            "trial in SECONDS = trial in progress (the unit of the stamp is not documented)"
        )

        // A trial that has expired = EXPIRE, whatever the unit.
        assertEquals(
            ProState.EXPIRE,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:$ilYAUneSemaineMs")),
            "trial expired in milliseconds = trial ended"
        )
        assertEquals(
            ProState.EXPIRE,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:$ilYAUneSemaineSec")),
            "trial expired in SECONDS = trial ended"
        )

        // An unreadable stamp stays INCONNU (we never guess).
        assertEquals(
            ProState.INCONNU,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:pas-un-nombre")),
            "an unreadable stamp gives no licence"
        )
    }

    // ------------------------------------------------------------------
    // Test 12 - the product code
    // ------------------------------------------------------------------

    @Test
    @DisplayName("12 - the product code is well formed and is written in one single place")
    fun leCodeProduitEstEcritAEcunEndroit() {
        // The 6 shape rules of the official documentation "add-required-parameters".
        val code = ProGate.CODE_PRODUIT
        assertTrue(code.startsWith("P"), "the product code must start with P")
        assertTrue(code.length in 4..15, "the product code must be 4 to 15 characters long (it is ${code.length})")
        assertTrue(code.all { it in 'A'..'Z' }, "the product code only contains UPPERCASE letters")
        assertEquals(code, "PPCOCKPITPRO")

        // The descriptor must carry exactly the same code.
        val descripteur = source("src/main/resources/META-INF/plugin.xml")
        val attendu = "<product-descriptor code=\"" + code + "\""
        assertTrue(
            descripteur.contains(attendu),
            "plugin.xml must carry <product-descriptor code=\"$code\" .../>, it carries:\n$descripteur"
        )

        // And the code must appear in **one single** Kotlin file of the main
        // sources: one unique constant, readable from the descriptor.
        val fichiers = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val porteurs = fichiers.filter { it.readText().contains(code) }
        assertEquals(
            1,
            porteurs.size,
            "the product code must only be written in ProGate.kt, it is in: " +
                porteurs.joinToString { it.name }
        )
        assertTrue(
            porteurs[0].name == "ProGate.kt",
            "the constant must live in ProGate.kt, it lives in ${porteurs[0].name}"
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun deuxRuns(): List<RunRef> = listOf(
        RunRef(id = "a", origine = "http://x:5000", params = mapOf("lr" to "0.1")),
        RunRef(id = "b", origine = "http://x:5000", params = mapOf("lr" to "0.1"))
    )

    /** Reads a file of the project (the working directory of the test = the project root). */
    private fun source(chemin: String): String {
        var d: File? = File(".").absoluteFile
        while (d != null) {
            val f = File(d, chemin)
            if (f.exists()) return f.readText(Charsets.UTF_8)
            d = d.parentFile
        }
        throw AssertionError("source not found: $chemin")
    }
}
