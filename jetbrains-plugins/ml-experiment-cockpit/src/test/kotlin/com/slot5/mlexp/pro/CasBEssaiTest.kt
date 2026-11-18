package com.slot5.mlexp.pro

import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **Les trois prefixes ouvrent la porte, et le « cas b » la laisse fermee**
 * — 2026.1.6, decision de la Direction du 18/11/2026.
 *
 * Regle : les trois prefixes de sceau — `key:` (licence par compte ou code
 * d'activation), `stamp:` (serveur flottant) et `eval:` (essai gratuit) —
 * sont tous les trois traites comme « Pro ouvert ». Le fail-closed ne change
 * pas : **rien de recu = pas de Pro**, quel que soit ce que le reglage de
 * vente du Marketplace dit par ailleurs.
 *
 * **LE CAS B, EN CLAIR (a ne pas masquer).** Le Marketplace annonce un essai
 * gratuit de 30 jours pour le palier Pro (`purchaseInfo.trialPeriod`).
 * La CROYANCE du code est que, pendant cet essai, la plateforme pose au
 * plugin un sceau `eval:` portant la date de fin — c'est ce sceau qui ouvre
 * la porte. **Cette croyance n'est pas encore verifiee sur un essai reel** :
 * si la plateforme ne renvoie NI `key:` NI `stamp:` NI `eval:` pendant
 * l'essai (cas b), la porte Pro reste FERMEE et l'utilisateur en essai lit
 * « No licence is active on this machine » sur l'onglet verrouille. Le test
 * ci-dessous documente ce comportement tel quel : il est VOLONTAIRE
 * (fail-closed, jamais de Pro gratuit « au doigt mouille »), et il doit
 * etre confirme ou infirme au PREMIER ESSAI REEL d'un utilisateur. Si le
 * cas b se produit vraiment, la correction viendra apres cette mesure.
 */
class CasBEssaiTest {

    @org.junit.jupiter.api.BeforeEach
    fun accepterLesSceauxDeTest() = ProGate.installerVerificateur(VerificateurDeTest.ACCEPTE)

    @org.junit.jupiter.api.AfterEach
    fun remettreLeVraiVerificateur() = ProGate.remettreVerificateurJetBrains()

    // ------------------------------------------------------------------
    // Helpers (meme principes que PanneauComparaisonPorteTest)
    // ------------------------------------------------------------------

    /** The panel that HOLDS the CardLayout (the gate). */
    private fun porteDe(panneau: PanneauComparaison): JPanel =
        panneau.rootComponent.components
            .filterIsInstance<JPanel>()
            .first { it.layout is CardLayout }

    /** True when the component (or one of its descendants) is a JTextArea. */
    private fun contientUnTextArea(c: Component): Boolean =
        (c is JTextArea) ||
            ((c as? Container)?.components?.any { contientUnTextArea(it) } ?: false)

    /** The visible card of the gate. */
    private fun carteVisible(porte: JPanel): Component =
        porte.components.first { it.isVisible }

    /** Every JLabel text of the panel, recursively (the badge lives there). */
    private fun textesDeLabel(c: Component): List<String> =
        listOfNotNull((c as? JLabel)?.text) +
            ((c as? Container)?.components?.flatMap { textesDeLabel(it) } ?: emptyList())

    /** Installs a licence source, runs the block, then restores the default one. */
    private fun avecLicence(source: SourceLicence, bloc: () -> Unit) {
        ProGate.installerSource(source)
        try {
            bloc()
        } finally {
            ProGate.installerSource(SourceLicenceAbsent)
        }
    }

    // ------------------------------------------------------------------
    // 1 - the three prefixes open the gate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("1 - key:, stamp: and eval: are all three 'Pro open': the content card is visible, the badge says 'Pro: active'")
    fun lesTroisPrefixesOuvrentLaPorte() {
        val dansUneHeure = System.currentTimeMillis() + 3_600_000L
        val sources = mapOf(
            "key: (account or activation code)" to
                LectureLicence(facadeInitialisee = true, sceau = "key:une-cle"),
            "stamp: (floating licence server)" to
                LectureLicence(facadeInitialisee = true, sceau = "stamp:un-serveur"),
            "eval: (free trial in progress)" to
                LectureLicence(facadeInitialisee = true, sceau = "eval:$dansUneHeure")
        )
        for ((nom, lecture) in sources) {
            assertTrue(ProGate.etat(lecture).autorise(), "$nom must authorise the Pro features")
            avecLicence(object : SourceLicence {
                override fun lire() = lecture
            }) {
                val panneau = PanneauComparaison(ComparaisonHub())
                val visible = carteVisible(porteDe(panneau))
                assertFalse(
                    contientUnTextArea(visible),
                    "$nom: the content card must be the visible one, not the lock card"
                )
                assertTrue(
                    textesDeLabel(panneau.rootComponent).any { it == "Pro: active" },
                    "$nom: the badge must say 'Pro: active'"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 2 - the badge no longer distinguishes a trial from a licence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2 - the interface does not distinguish a trial from a paid licence: same badge text for key: and for eval:")
    fun leBadgeNeDistinguePlusEssaiEtLicence() {
        val dansUneHeure = System.currentTimeMillis() + 3_600_000L
        val textesBadge = mutableListOf<String>()
        for (sceau in listOf("key:une-cle", "eval:$dansUneHeure")) {
            avecLicence(object : SourceLicence {
                override fun lire() = LectureLicence(facadeInitialisee = true, sceau = sceau)
            }) {
                val panneau = PanneauComparaison(ComparaisonHub())
                textesBadge += textesDeLabel(panneau.rootComponent).filter { it.startsWith("Pro:") }
            }
        }
        assertEquals(
            2,
            textesBadge.size,
            "each of the two readings must expose exactly one 'Pro:' badge (found: $textesBadge)"
        )
        assertEquals(
            textesBadge[0],
            textesBadge[1],
            "the badge must not tell a paid licence (${textesBadge[0]}) from a trial " +
                "(${textesBadge[1]}): both must read 'Pro: active'"
        )
    }

    // ------------------------------------------------------------------
    // 3 - THE CASE B, stated as it is (a belief to verify at the first
    //     real user trial)
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
        "CAS B - during a trial, if the platform returns neither key: nor stamp: nor eval:, the Pro gate stays CLOSED " +
            "(documented belief: to verify at the FIRST REAL user trial)"
    )
    fun casB_essaiSansSceau_laPorteResteFermee() {
        // The sale settings of the Marketplace grant a 30-day trial, BUT the
        // licence facade answers "no stamp at all" (facadeInitialisee = true,
        // sceau = null): the case b. Nothing received = no Pro: the gate
        // stays closed (fail-closed), and the locked card says "No licence
        // is active on this machine." - even though the user IS in trial.
        val casB = LectureLicence(facadeInitialisee = true, sceau = null)

        assertEquals(
            ProState.ABSENT,
            ProGate.etat(casB),
            "case b: no key:, no stamp:, no eval: => the state must be ABSENT, never a guess"
        )
        assertFalse(
            ProGate.etat(casB).autorise(),
            "case b: the Pro features must NOT open on an empty answer"
        )

        avecLicence(object : SourceLicence {
            override fun lire() = casB
        }) {
            val panneau = PanneauComparaison(ComparaisonHub())
            val visible = carteVisible(porteDe(panneau))
            assertTrue(
                contientUnTextArea(visible),
                "case b: the LOCK card must be the visible one (fail-closed)"
            )
            val message = (visible as JPanel).components.filterIsInstance<JTextArea>().firstOrNull()
            assertNotNull(message, "case b: the lock card must hold the message area")
            assertTrue(
                message!!.text.contains("No licence is active on this machine."),
                "case b, stated as it is: the user in trial reads \"No licence is active on this " +
                    "machine.\" - a BELIEF TO VERIFY at the first real user trial"
            )
            assertTrue(
                textesDeLabel(panneau.rootComponent).any { it == "Pro: not active - no licence" },
                "case b: the badge must say 'Pro: not active - no licence' (not 'Pro: active')"
            )
        }
    }
}
