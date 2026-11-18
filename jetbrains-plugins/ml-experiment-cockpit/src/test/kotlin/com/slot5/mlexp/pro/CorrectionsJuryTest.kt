package com.slot5.mlexp.pro

import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import java.io.File
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **The 3 corrections of the jury (review 8f1f of 13/11/2026, 0 blocking).**
 *
 * They are NOT blocking for the Marketplace, but they are real defects for the
 * customer, so they are corrected in the source of the plugin (the version
 * 2026.1.3 that comes after the review of the code). Each correction is
 * proved here by a test that is RED on the 2026.1.2 code and GREEN after the
 * correction:
 *
 *   - **J1 (point a)** after a purchase finished in the browser, the licence
 *     can still be read as "no licence" while the payment is done. The jury
 *     proposes a **"Check licence again" button on the locked card**. The
 *     button re-reads the licence and unlocks the tab when the licence is
 *     active. (No official documentation describes a licence change event, so
 *     the button is the correction chosen - a manual, explicit gesture.)
 *   - **J2 (point b)** the **"Get Pro" button stays visible for a customer
 *     who already has the Pro licence**: it must be hidden as soon as the
 *     licence authorises the Pro features.
 *   - **J3 (point c)** the comment of the purchase dialog says
 *     "no amount is written in the code": it has been false since the 2026.1.1
 *     (the price is hard-coded on the locked card). The comment must tell the
 *     truth, and the test checks that the price really is in the code.
 *
 * The panel built here is the **production panel** (`PanneauComparaison`), the
 * licence answer is a **double** (no secret, no licence, no token) and
 * nothing else is emulated.
 */
class CorrectionsJuryTest {

    @org.junit.jupiter.api.BeforeEach
    fun accepterLesSceauxDeTest() = ProGate.installerVerificateur(VerificateurDeTest.ACCEPTE)

    @org.junit.jupiter.api.AfterEach
    fun remettreLeVraiVerificateur() = ProGate.remettreVerificateurJetBrains()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The licence answer of the platform, changed at will by the test. */
    private class SourceLicenceFaux(var sceau: String?) : SourceLicence {
        override fun lire(): LectureLicence =
            LectureLicence(facadeInitialisee = true, sceau = sceau)
    }

    /** Installs a licence source, runs the block, then restores the default one. */
    private fun avecLicence(source: SourceLicence, bloc: () -> Unit) {
        ProGate.installerSource(source)
        try {
            bloc()
        } finally {
            ProGate.installerSource(SourceLicenceAbsent)
        }
    }

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

    /** Every button of the component (and of its descendants). */
    private fun boutonsDe(c: Component): List<JButton> {
        val liste = mutableListOf<JButton>()
        if (c is JButton) liste.add(c)
        if (c is Container) for (enfant in c.components) liste.addAll(boutonsDe(enfant))
        return liste
    }

    /** The source of the panel (read as text, like the other relecture tests). */
    private fun sourceDuPanneau(): String {
        val f = File("src/main/kotlin/com/slot5/mlexp/pro/PanneauComparaison.kt")
        assertTrue(f.exists(), "the source of the panel must exist: ${f.absolutePath}")
        return f.readText(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------
    // J1 - the "Check licence again" button on the locked card
    // ------------------------------------------------------------------

    @Test
    @DisplayName("J1 - the locked card offers a \"Check licence again\" button that re-reads the licence")
    fun leBoutonDeRelicenceRelitLaLicence() {
        val source = SourceLicenceFaux(sceau = null)
        avecLicence(source) {
            val panneau = PanneauComparaison(ComparaisonHub())
            val porte = porteDe(panneau)

            // 1. the free customer is locked.
            assertTrue(
                contientUnTextArea(carteVisible(porte)),
                "without a licence the Compare tab must stay locked"
            )

            // 2. the locked card offers the button.
            val bouton = boutonsDe(carteVisible(porte)).firstOrNull { it.text == "Check licence again" }
            assertNotNull(
                bouton,
                "the locked card must offer a \"Check licence again\" button"
            )

            // 3. the purchase finished in the browser: the licence is active now.
            source.sceau = "key:test-key-no-secret"

            // 4. the customer clicks the button: the licence is read again.
            bouton!!.doClick(0)

            assertFalse(
                contientUnTextArea(carteVisible(porte)),
                "after \"Check licence again\" the tab must unlock when the licence is active"
            )
        }
    }

    @Test
    @DisplayName("J1 bis - \"Check licence again\" keeps the tab locked when the licence is still missing")
    fun leBoutonDeRelicenceNeDeverrouillePasSansLicence() {
        val source = SourceLicenceFaux(sceau = null)
        avecLicence(source) {
            val panneau = PanneauComparaison(ComparaisonHub())
            val porte = porteDe(panneau)
            val bouton = boutonsDe(carteVisible(porte)).firstOrNull { it.text == "Check licence again" }
            assertNotNull(bouton, "the locked card must offer a \"Check licence again\" button")

            bouton!!.doClick(0)

            assertTrue(
                contientUnTextArea(carteVisible(porte)),
                "without a licence the tab must stay locked after \"Check licence again\""
            )
        }
    }

    // ------------------------------------------------------------------
    // J2 - the "Get Pro" button is hidden for a customer who has the licence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("J2 - the \"Get Pro\" button is hidden when the Pro licence authorises the features")
    fun leBoutonGetProEstCachePourUnClientPro() {
        val source = SourceLicenceFaux(sceau = "key:test-key-no-secret")
        avecLicence(source) {
            val panneau = PanneauComparaison(ComparaisonHub())
            val bouton = boutonsDe(panneau.rootComponent).firstOrNull { it.text == "Get Pro" }
            assertNotNull(bouton, "the panel must hold the \"Get Pro\" button")
            assertFalse(
                bouton!!.isVisible,
                "a customer who already has the Pro licence must not see \"Get Pro\""
            )
        }
    }

    @Test
    @DisplayName("J2 bis - the \"Get Pro\" button stays visible for a customer without licence")
    fun leBoutonGetProResteVisiblePourUnClientGratuit() {
        val source = SourceLicenceFaux(sceau = null)
        avecLicence(source) {
            val panneau = PanneauComparaison(ComparaisonHub())
            val bouton = boutonsDe(panneau.rootComponent).firstOrNull { it.text == "Get Pro" }
            assertNotNull(bouton, "the panel must hold the \"Get Pro\" button")
            assertTrue(
                bouton!!.isVisible,
                "a customer without licence must find \"Get Pro\""
            )
        }
    }

    // ------------------------------------------------------------------
    // J3 - the comment of the purchase dialog tells the truth about the price
    // ------------------------------------------------------------------

    @Test
    @DisplayName("J3 - the comment of the purchase dialog does not claim that no amount is written in the code")
    fun leCommentaireDuPrixEstJuste() {
        val source = sourceDuPanneau()
        assertFalse(
            source.contains("no amount is written in the code"),
            "the comment must not claim that no amount is written in the code: " +
                "the price is hard-coded on the locked card"
        )
        assertTrue(
            source.lowercase().contains("the price is written in the code"),
            "the comment must say that the price is written in the code"
        )
        assertTrue(
            source.contains("ML Experiment Cockpit Pro - \$29/year, free trial."),
            "the price must really be written in the code: the label of the locked card"
        )
    }
}
