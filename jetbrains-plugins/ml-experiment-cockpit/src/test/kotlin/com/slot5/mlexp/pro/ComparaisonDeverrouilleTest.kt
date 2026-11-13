package com.slot5.mlexp.pro

import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **PROOF: the Pro tier really unlocks the "Compare" tab.**
 *
 * This is the path that answers "is this licence active?" - the one that
 * decides whether the first paying customer sees the paid feature or a lock.
 * It is exercised on the **real panel** (`PanneauComparaison`, the production
 * class), in a bare JVM, with **two doubles and nothing else**:
 *
 *   - [SourceLicence] (the licence seam, already in the production code): the
 *     double answers what the JetBrains platform would answer. **No secret, no
 *     licence, no token** - the strings are made up;
 *   - [AcheteurPro] (the purchase seam): the double stands for the JetBrains
 *     purchase dialog, which needs a running IDE and cannot be called from a
 *     test.
 *
 * **The real licence was NOT exercised** (there is no licence here, and none
 * can be obtained from inside the container): what is proven is the *decision
 * path* - from the platform's answer to the visible card of the tab.
 *
 * The official reference of that path is JetBrains' own sample,
 * `CheckLicense.java` ("Add license verification calls to the plugin code"):
 * `LicensingFacade.getInstance() == null` -> the licence cannot be checked
 * yet; a null stamp -> the licence is missing; a stamp prefixed `key:`,
 * `stamp:` or `eval:` -> a licence. The prefixes used here are exactly those
 * of the official sample.
 */
class ComparaisonDeverrouilleTest {

    // ------------------------------------------------------------------
    // The doubles (no secret, no licence, no token)
    // ------------------------------------------------------------------

    /** The licence answer of the platform, changed at will by the test. */
    private class SourceLicenceFaux(var sceau: String?) : SourceLicence {
        override fun lire(): LectureLicence =
            LectureLicence(facadeInitialisee = true, sceau = sceau)
    }

    /** The purchase dialog: it records the product and simulates the purchase. */
    private class AcheteurFaux(private val surAchat: () -> Unit) : AcheteurPro {
        var codeProduit: String? = null
        var message: String? = null

        override fun ouvrir(codeProduit: String, message: String, surFermeture: (String?) -> Unit) {
            this.codeProduit = codeProduit
            this.message = message
            surAchat()
            surFermeture(null)
        }
    }

    // ------------------------------------------------------------------
    // Helpers (the panel is read, never guessed)
    // ------------------------------------------------------------------

    /** The panel that HOLDS the CardLayout (the gate). */
    private fun porteDe(panneau: PanneauComparaison): JPanel =
        panneau.rootComponent.components
            .filterIsInstance<JPanel>()
            .first { it.layout is java.awt.CardLayout }

    /** True when the component (or one of its descendants) is a JTextArea. */
    private fun contientUnTextArea(c: Component): Boolean =
        (c is JTextArea) ||
            ((c as? Container)?.components?.any { contientUnTextArea(it) } ?: false)

    /** The visible card of the gate. */
    private fun carteVisible(porte: JPanel): Component =
        porte.components.first { it.isVisible }

    /** Every button of the panel. */
    private fun boutonsDe(c: Component): List<JButton> {
        val liste = mutableListOf<JButton>()
        if (c is JButton) liste.add(c)
        if (c is Container) for (enfant in c.components) liste.addAll(boutonsDe(enfant))
        return liste
    }

    // ------------------------------------------------------------------
    // C1 - the first paying customer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C1 - the first paying customer: the Compare tab unlocks once the licence is active")
    fun lePremierClientPayantVoitLComparaison() {
        // The machine starts with NO licence for the product (the free user).
        val source = SourceLicenceFaux(sceau = null)
        ProGate.installerSource(source)
        try {
            val acheteur = AcheteurFaux { source.sceau = "key:test-key-no-secret" }
            val panneau = PanneauComparaison(ComparaisonHub(), acheteur)
            val porte = porteDe(panneau)

            // 1. The free user: the tab is LOCKED...
            assertTrue(
                contientUnTextArea(carteVisible(porte)),
                "without a licence the Compare tab must stay locked"
            )
            // ... and the "Get Pro" button is there.
            val bouton = boutonsDe(panneau.rootComponent).firstOrNull { it.text == "Get Pro" }
            assertNotNull(bouton, "the free user must find the \"Get Pro\" button")

            // 2. The customer clicks "Get Pro" and the purchase completes.
            bouton!!.doClick(0)

            // 3. The dialog was opened for OUR product...
            assertEquals(
                ProGate.CODE_PRODUIT,
                acheteur.codeProduit,
                "the purchase dialog must be opened for our own product code"
            )

            // 4. ... and the Compare tab is now UNLOCKED.
            assertFalse(
                contientUnTextArea(carteVisible(porte)),
                "the Compare tab must unlock as soon as the licence is active"
            )
        } finally {
            ProGate.installerSource(SourceLicenceAbsent)
        }
    }

    // ------------------------------------------------------------------
    // C2 - the 30-day trial of the Marketplace
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C2 - the trial in progress lets the Compare tab through")
    fun lEssaiEnCoursLaissePasserLComparaison() {
        // The stamp of a trial carries the date at which it ends (the official
        // sample reads it as a timestamp in milliseconds).
        val finEssai = System.currentTimeMillis() + 30L * 86_400_000L
        val source = SourceLicenceFaux(sceau = "eval:" + finEssai)
        ProGate.installerSource(source)
        try {
            val panneau = PanneauComparaison(ComparaisonHub())
            val porte = porteDe(panneau)
            assertFalse(
                contientUnTextArea(carteVisible(porte)),
                "a trial in progress must open the Compare tab"
            )
        } finally {
            ProGate.installerSource(SourceLicenceAbsent)
        }
    }

    // ------------------------------------------------------------------
    // C3 - the trial that has ended
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C3 - the trial that has ended keeps the Compare tab locked")
    fun lEssaiTermineLaisseLaComparaisonVerrouillee() {
        val finEssai = System.currentTimeMillis() - 86_400_000L
        val source = SourceLicenceFaux(sceau = "eval:" + finEssai)
        ProGate.installerSource(source)
        try {
            val panneau = PanneauComparaison(ComparaisonHub())
            val porte = porteDe(panneau)
            assertTrue(
                contientUnTextArea(carteVisible(porte)),
                "an ended trial must keep the Compare tab locked"
            )
        } finally {
            ProGate.installerSource(SourceLicenceAbsent)
        }
    }
}
