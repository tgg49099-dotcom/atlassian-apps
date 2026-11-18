package com.slot5.mlexp.pro

import com.sun.net.httpserver.HttpServer
import java.awt.CardLayout
import java.awt.Component
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import javax.swing.JPanel
import javax.swing.JTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **Les 3 tests de la PORTE PRO** (demande de la mere, 17/11/2026).
 *
 * Objectif : prouver que le chemin de vente du plugin 35416 n'est pas casse,
 * c'est-a-dire que la porte payante repond **exactement** comme le veut
 * l'API officielle JetBrains :
 *
 * | Test | Cas | Attendu |
 * |---|---|---|
 * | (a) | une licence VALIDE selon l'API officielle | **ACCEPTEE** (`ProState.PRO`) |
 * | (b) | un sceau forge (faux, bricole, certificat pirate) | **REFUSE** (`ProState.ABSENT`) |
 * | (c) | aucune licence | la version GRATUITE reste utilisable (pas de crash, pas de fenetre bloquee) |
 *
 * **Ce qui est officiel, et ce qui ne l'est pas.**
 * - Le format du sceau est celui de l'echantillon officiel `CheckLicense.java`
 *   (depot `JetBrains/marketplace-makemecoffee-plugin`) : `key:<licenseId>-<licence>-<signature>-<certificat>`.
 * - Le **verificateur** utilise ici est le **verificateur de production**
 *   (`VerificationJetBrains` / `VerificationSceau.kt`) : chaine PKIX jusqu'a une
 *   racine JetBrains, signature `SHA1withRSA`, coherence du `licenseId`.
 * - La seule difference dans le test (a) : l'**ancre de confiance** est une
 *   racine de TEST (`sceaux-de-test.properties`), pas la racine JetBrains.
 *   C'est la seule facon de tester une licence valide **sans en acheter** :
 *   le code de verification est execute tel quel, seule l'ancre change.
 *   **Nous n'avons pas de licence achetee et ce test ne pretend pas en avoir une.**
 * - Le test (b) utilise le verificateur **de production** (racines JetBrains) :
 *   la, un sceau forge est refuse par le vrai code.
 *
 * Lancement en 1 commande : `bash /work/OUTILS/test-licence-pro.sh`
 * (ou `./gradlew test --tests 'com.slot5.mlexp.pro.PorteProLicenceTest'`).
 */
class PorteProLicenceTest {

    @org.junit.jupiter.api.BeforeEach
    fun verifierParDefaut() = ProGate.installerVerificateur(VerificationJetBrains())

    @org.junit.jupiter.api.AfterEach
    fun vraiVerificateur() = ProGate.remettreVerificateurJetBrains()

    // ==================================================================
    // (a) une licence VALIDE selon l'API officielle -> ACCEPTEE
    // ==================================================================

    @Test
    @DisplayName("(a) a licence conforming to the official API (signed, certificate chained to a trusted root) is ACCEPTED")
    fun licenceValideEstAcceptee() {
        val sceau = VerificateurDeTest.sceau("cle_valide")
        val corps = sceau.removePrefix(ProGate.PREFIXE_CLE)

        // 1. le verificateur de PRODUCTION accepte le sceau (racine de test)
        val verificateur = VerificateurDeTest.racineDeTest()
        assertTrue(
            verificateur.cleValide(corps),
            "the verifier of production must accept a stamp whose signature and chain are valid"
        )

        // 2. la PORTE l'accepte : l'etat est PRO, la licence est autorisee
        ProGate.installerVerificateur(verificateur)
        val etat = ProGate.etatDepuisSceau(sceau)
        assertEquals(ProState.PRO, etat, "a valid licence must open the Pro tier")
        assertTrue(etat.autorise(), "a valid licence must be authorised")

        // 3. la fonctionnalite payante s'ouvre vraiment (pas seulement l'etat)
        val resultat = ComparaisonRuns.calculer(deuxRuns(), etat)
        assertTrue(
            resultat is ResultatComparaison.Comparaison,
            "with a valid licence the comparison must be produced, got: $resultat"
        )

        // 4. le verificateur de PRODUCTION (racines JetBrains) refuse ce sceau :
        //    il est signe par la racine de TEST, donc ce n'est PAS une licence
        //    JetBrains. C'est la preuve que l'ancre est ce qui decide.
        assertFalse(
            VerificationJetBrains().cleValide(corps),
            "a stamp signed by a test root must not pass the JetBrains roots"
        )
    }

    // ==================================================================
    // (b) un sceau FORGE -> REFUSE
    // ==================================================================

    @Test
    @DisplayName("(b) a forged seal (hand-written, tampered signature, pirate certificate, other licence id) is REFUSED")
    fun sceauFalsifieEstRefuse() {
        // Le verificateur de PRODUCTION est installe (BeforeEach) : ancres JetBrains.
        val forges = listOf(
            "key:xyz" to "hand-written stamp (no signature at all)",
            "key:" to "empty stamp",
            VerificateurDeTest.sceau("cle_pirate") to
                "certificate that claims to be 'JetProfile CA' but is not signed by the JetBrains root",
            VerificateurDeTest.sceau("cle_donnees_modifiees") to
                "licence data changed after the signature",
            VerificateurDeTest.sceau("cle_autre_id") to
                "licenceId in front differs from the signed one",
            VerificateurDeTest.sceau("cle_trois_parties") to
                "3 parts instead of 4",
            "stamp:MACHINE1:1800000000000:MACHINE1:SHA1withRSA:AAAA:BBBB" to
                "floating-server stamp with a garbage signature"
        )
        for ((sceau, raison) in forges) {
            assertEquals(
                ProState.ABSENT,
                ProGate.etatDepuisSceau(sceau),
                "a forged seal must be REFUSED ($raison)"
            )
            assertFalse(
                ProState.ABSENT.autorise(),
                "a forged seal must not authorise the Pro tier ($raison)"
            )
        }

        // Meme avec la racine de TEST, un sceau bricole est refuse par le
        // verificateur lui-meme (signature ou donnees alterees).
        val verificateurTest = VerificateurDeTest.racineDeTest()
        assertFalse(
            verificateurTest.cleValide(VerificateurDeTest.sceau("cle_donnees_modifiees").removePrefix(ProGate.PREFIXE_CLE)),
            "data changed after signing must be refused"
        )
        assertFalse(
            verificateurTest.cleValide(VerificateurDeTest.sceau("cle_autre_id").removePrefix(ProGate.PREFIXE_CLE)),
            "another licenceId must be refused"
        )
        assertFalse(
            verificateurTest.cleValide(VerificateurDeTest.sceau("cle_pirate").removePrefix(ProGate.PREFIXE_CLE)),
            "a pirate certificate must be refused"
        )
    }

    // ==================================================================
    // (c) aucune licence -> la version GRATUITE reste utilisable
    // ==================================================================

    @Test
    @DisplayName("(c) without any licence, the free version stays usable: no crash, no blocked window")
    fun sansLicenceLeGratuitResteUtilisable() {
        // 1. la porte repond ABSENT, sans exception
        val etat = ProGate.etat(LectureLicence(facadeInitialisee = true, sceau = null))
        assertEquals(ProState.ABSENT, etat, "without a licence the state must be ABSENT")
        assertFalse(etat.autorise(), "without a licence no Pro feature is authorised")

        // 2. PAS DE CRASH : le vrai panneau Pro se construit (code de production)
        ProGate.installerSource(object : SourceLicence {
            override fun lire() = LectureLicence(facadeInitialisee = true, sceau = null)
        })
        try {
            val panneau = PanneauComparaison(ComparaisonHub())
            assertNotNull(panneau.rootComponent, "the panel must build without exception")
            val porte = porteDe(panneau)
            assertEquals(2, porte.componentCount, "the gate must hold its 2 cards")
            // 3. PAS DE FENETRE BLOQUEE : la carte visible est le message de
            //    vente (2026.1.7), pas une fenetre qui gele l'IDE.
            val visible = carteVisible(porte)
            assertTrue(
                contientUnTextArea(visible),
                "the visible card must be the lock card (a message), not a blocked window"
            )
            val message = (visible as JPanel).components.filterIsInstance<JTextArea>().firstOrNull()
            assertNotNull(message, "the lock card must hold the message area")
            // 2026.1.7: le message de verrou est le message de vente honnete
            // (l'etat "pas de licence" reste sur le badge, pas dans le texte).
            assertTrue(
                message!!.text.contains("Compare is a Pro feature."),
                "the free user must read the sales message \"Compare is a Pro feature.\", got: ${message.text}"
            )
        } finally {
            ProGate.installerSource(SourceLicenceAbsent)
        }

        // 4. la fonctionnalite payante est refusee PROPREMENT (pas un crash)
        val resultat = ComparaisonRuns.calculer(deuxRuns(), ProState.ABSENT)
        assertTrue(resultat is ResultatComparaison.Refus, "without a licence the comparison must be refused")
        assertEquals(
            CodeRefus.BLOQUE_SANS_LICENCE,
            (resultat as ResultatComparaison.Refus).code,
            "the refusal must name the missing licence"
        )

        // 5. le chemin GRATUIT fonctionne toujours : le client parle au serveur,
        //    avec ses limites annoncees (aucune limite retiree).
        val faux = FauxServeur()
        try {
            val base = faux.demarrer()
            val client = com.slot5.mlexp.MlflowClient(base)
            client.listExperiments()
            assertTrue(
                faux.dernierCorps()!!.contains("\"max_results\":500"),
                "without a licence, listExperiments must still ask for 500 experiments"
            )
            client.listRuns("exp1")
            assertTrue(
                faux.dernierCorps()!!.contains("\"max_results\":100"),
                "without a licence, listRuns must still ask for 100 runs"
            )
        } finally {
            faux.arreter()
        }

        // 6. quand la plateforme ne peut pas repondre, la porte le dit
        //    (INCONNU) au lieu de lever : le gratuit reste utilisable.
        assertEquals(
            ProState.INCONNU,
            ProGate.etat(LectureLicence(facadeInitialisee = false, sceau = null, motif = "IDE starting")),
            "an unreadable licence must be INCONNU, never an exception"
        )
        assertFalse(ProState.INCONNU.autorise(), "INCONNU never opens a paid feature")
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private fun deuxRuns(): List<RunRef> = listOf(
        RunRef(id = "r1", origine = "http://127.0.0.1:5000"),
        RunRef(id = "r2", origine = "http://127.0.0.1:5000")
    )

    /** The panel that HOLDS the CardLayout (the gate). */
    private fun porteDe(panneau: PanneauComparaison): JPanel =
        panneau.rootComponent.components
            .filterIsInstance<JPanel>()
            .first { it.layout is CardLayout }

    /** True when the component (or one of its descendants) is a JTextArea. */
    private fun contientUnTextArea(c: Component): Boolean =
        (c is JTextArea) ||
            ((c as? java.awt.Container)?.components?.any { contientUnTextArea(it) } ?: false)

    /** The visible card of the gate. */
    private fun carteVisible(porte: JPanel): Component =
        porte.components.first { it.isVisible }

    /** Fake MLflow server: records what is asked, answers `{}`. */
    private class FauxServeur {
        private lateinit var server: HttpServer
        private val corps = ArrayList<String>()

        fun demarrer(): String {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                corps.add(body)
                val reponse = "{}".toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, reponse.size.toLong())
                exchange.responseBody.use { it.write(reponse) }
            }
            server.start()
            return "http://127.0.0.1:${server.address.port}"
        }

        fun dernierCorps(): String? = if (corps.isEmpty()) null else corps[corps.size - 1]
        fun arreter() = server.stop(0)
    }
}
