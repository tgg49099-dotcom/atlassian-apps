package com.slot5.mlexp.pro

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests de la **porte Pro** du palier n°1.
 *
 * v0.0.22. La porte est testée **sans IDE** : elle ne connaît que la forme
 * de la réponse de la plateforme (`LectureLicence`), jamais la classe
 * IntelliJ. Les 4 tests du plan (`/work/cockpit-pro-n1-plan.md` § 4) :
 *   1. sans licence → la comparaison est bloquée ;
 *   2. avec licence → la comparaison passe ;
 *   3. licence expirée → la comparaison est bloquée, avec le motif exact ;
 *  12. le code produit est écrit à un seul endroit et il est cohérent avec
 *      le descripteur `plugin.xml`.
 */
class ProGateTest {

    // ------------------------------------------------------------------
    // Test 1 — sans licence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1 - sans licence : la comparaison est bloquée, avec un motif")
    fun sansLicence_laComparaisonEstBloquee() {
        val etat = ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = null))
        assertEquals(ProState.ABSENT, etat, "pas de sceau = pas de licence")

        val r = ComparaisonRuns.calculer(deuxRuns(), etat)
        assertTrue(r is ResultatComparaison.Refus, "sans licence, rien ne doit être calculé : $r")
        val refus = r as ResultatComparaison.Refus
        assertEquals(CodeRefus.BLOQUE_SANS_LICENCE, refus.code)
        assertTrue(refus.message.isNotBlank(), "le refus doit avoir un motif lisible")
    }

    // ------------------------------------------------------------------
    // Test 2 — avec licence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2 - avec licence : la comparaison passe (0 écart sur 2 runs identiques)")
    fun avecLicence_laComparaisonPasse() {
        val etat = ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "key:une-cle"))
        assertEquals(ProState.PRO, etat)

        val r = ComparaisonRuns.calculer(deuxRuns(), etat)
        assertTrue(r is ResultatComparaison.Comparaison, "avec licence, la comparaison doit aboutir : $r")
        assertEquals(0, (r as ResultatComparaison.Comparaison).nombreEcarts)
    }

    // ------------------------------------------------------------------
    // Test 3 — licence expirée
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3 - essai terminé : la comparaison est bloquée avec le motif exact")
    fun licenceExpiree_laComparaisonEstBloquee() {
        // Un sceau d'essai porte la date de fin (en millisecondes) : ici, une
        // date passée = essai terminé.
        val sceau = "eval:" + (System.currentTimeMillis() - 60_000L)
        val etat = ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = sceau))
        assertEquals(ProState.EXPIRE, etat)

        val r = ComparaisonRuns.calculer(deuxRuns(), etat)
        assertTrue(r is ResultatComparaison.Refus, "un essai terminé doit bloquer : $r")
        val refus = r as ResultatComparaison.Refus
        assertEquals(CodeRefus.LICENCE_EXPIREE, refus.code)
        assertTrue(
            refus.message.contains("essai"),
            "le motif doit nommer l'essai, il dit : ${refus.message}"
        )
    }

    // ------------------------------------------------------------------
    // Test 3 bis — les autres états
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3 bis - essai en cours, facade non initialisée, préfixe inconnu")
    fun lesAutresEtatsDeLaPorte() {
        val futur = System.currentTimeMillis() + 86_400_000L
        assertEquals(
            ProState.ESSAI,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:$futur")),
            "un sceau d'essai non echu = essai en cours"
        )
        assertEquals(
            ProState.INCONNU,
            ProGate.etat(LectureLicence(facadeInitialisee = false)),
            "facade non initialise = on ne peut pas conclure"
        )
        assertEquals(
            ProState.ABSENT,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "quelque-chose:autre")),
            "un préfixe inconnu n'est pas une licence"
        )
        assertEquals(
            ProState.PRO,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "stamp:serveur-flottant")),
            "un sceau de serveur flottant = licence"
        )
        assertTrue(ProState.ESSAI.autorise(), "l'essai autorise la comparaison")
        assertTrue(!ProState.INCONNU.autorise(), "l'inconnu n'ouvre rien")
    }

    // ------------------------------------------------------------------
    // Test 3 ter — l'unité de l'horodatage du sceau d'essai
    // ------------------------------------------------------------------

    @Test
    @DisplayName("3 ter - l'essai se lit en millisecondes ET en secondes (l'unité n'est pas documentée)")
    fun essaiSceauLuDansLesDeuxUnites() {
        val maintenant = System.currentTimeMillis()
        val dansUneSemaineMs = maintenant + 7L * 86_400_000L
        val dansUneSemaineSec = dansUneSemaineMs / 1000L
        val ilYAUneSemaineMs = maintenant - 7L * 86_400_000L
        val ilYAUneSemaineSec = ilYAUneSemaineMs / 1000L

        // Un essai qui n'est pas echu = ESSAI, quelle que soit l'unite.
        assertEquals(
            ProState.ESSAI,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:$dansUneSemaineMs")),
            "essai en millisecondes = essai en cours"
        )
        assertEquals(
            ProState.ESSAI,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:$dansUneSemaineSec")),
            "essai en SECONDES = essai en cours (l'unite du sceau n'est pas documentee)"
        )

        // Un essai echu = EXPIRE, quelle que soit l'unite.
        assertEquals(
            ProState.EXPIRE,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:$ilYAUneSemaineMs")),
            "essai echu en millisecondes = essai termine"
        )
        assertEquals(
            ProState.EXPIRE,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:$ilYAUneSemaineSec")),
            "essai echu en SECONDES = essai termine"
        )

        // Un sceau illisible reste INCONNU (on ne devine pas).
        assertEquals(
            ProState.INCONNU,
            ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = "eval:pas-un-nombre")),
            "un sceau illisible ne donne pas de licence"
        )
    }

    // ------------------------------------------------------------------
    // Test 12 — le code produit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("12 - le code produit est conforme et n'est écrit qu'à un seul endroit")
    fun leCodeProduitEstEcritAEcunEndroit() {
        // Les 6 règles de forme de la doc officielle « add-required-parameters ».
        val code = ProGate.CODE_PRODUIT
        assertTrue(code.startsWith("P"), "le code produit doit commencer par P")
        assertTrue(code.length in 4..15, "le code produit doit faire 4 à 15 caractères (il en fait ${code.length})")
        assertTrue(code.all { it in 'A'..'Z' }, "le code produit ne contient que des MAJUSCULES")
        assertEquals(code, "PPCOCKPITPRO")

        // Le descripteur doit porter exactement le même code.
        val descripteur = source("src/main/resources/META-INF/plugin.xml")
        val attendu = "<product-descriptor code=\"" + code + "\""
        assertTrue(
            descripteur.contains(attendu),
            "plugin.xml doit porter <product-descriptor code=\"$code\" .../>, il porte :\n$descripteur"
        )

        // Et le code ne doit apparaître dans **un seul** fichier Kotlin des sources
        // principales : une constante unique, lisible par le descripteur.
        val fichiers = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val porteurs = fichiers.filter { it.readText().contains(code) }
        assertEquals(
            1,
            porteurs.size,
            "le code produit ne doit être écrit que dans ProGate.kt, il est dans : " +
                porteurs.joinToString { it.name }
        )
        assertTrue(
            porteurs[0].name == "ProGate.kt",
            "la constante doit vivre dans ProGate.kt, elle est dans ${porteurs[0].name}"
        )
    }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    private fun deuxRuns(): List<RunRef> = listOf(
        RunRef(id = "a", origine = "http://x:5000", params = mapOf("lr" to "0.1")),
        RunRef(id = "b", origine = "http://x:5000", params = mapOf("lr" to "0.1"))
    )

    /** Lit un fichier du projet (le répertoire de travail du test = la racine du projet). */
    private fun source(chemin: String): String {
        var d: File? = File(".").absoluteFile
        while (d != null) {
            val f = File(d, chemin)
            if (f.exists()) return f.readText(Charsets.UTF_8)
            d = d.parentFile
        }
        throw AssertionError("source introuvable : $chemin")
    }
}
