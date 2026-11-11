package com.slot5.mlexp.pro

import java.awt.CardLayout
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.event.HierarchyEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **Contre-épreuve de la PORTE (CardLayout) du panneau Compare** — cycle 158.
 *
 * **Le défaut trouvé (mesuré dans un IDE réel, PyCharm 2026.2.4).**
 * Le ZIP livré s'installe et se charge, mais l'onglet **Compare** n'existe
 * pas : à l'ouverture du tool window, la construction du panneau lève
 *
 * ```
 * java.lang.IllegalArgumentException: wrong parent for CardLayout
 *     at java.awt.CardLayout.checkLayout(CardLayout.java:413)
 *     at java.awt.CardLayout.show(CartLayout.java:535)
 *     at com.slot5.mlexp.pro.PanneauComparaison.<init>(PanneauComparaison.kt:108)
 *     at com.slot5.mlexp.MlflowToolWindowFactory.createToolWindowContent(...:97)
 * ```
 *
 * **Cause** (source du JDK, `java.desktop/java/awt/CardLayout.java`) :
 * ```java
 * void checkLayout(Container parent) {
 *     if (parent.getLayout() != this) {
 *         throw new IllegalArgumentException("wrong parent for CardLayout");
 *     }
 * public void show(Container parent, String name) { checkLayout(parent); ... }
 * ```
 * Le 1er argument de `show` est le **conteneur** qui porte le `CardLayout`,
 * pas la carte à afficher. Le code passait la carte. En prime, les cartes
 * n'étaient pas des enfants du conteneur (donc jamais affichées).
 *
 * **Conséquence mesurée dans l'IDE** : les 4 onglets gratuits s'affichent,
 * l'onglet **Compare** (le palier Pro n°1) est **absent**, et l'IDE affiche
 * un ballon rouge « IDE error occurred ».
 *
 * Ces tests sont le **rouge** de la contre-épreuve : ils construisent le
 * panneau réel (le code de production, pas une copie) et exigent qu'il se
 * construise, que la porte ait ses 2 cartes, et que la porte affiche la
 * bonne carte selon la licence. Avant la correction ils échouent sur
 * l'`IllegalArgumentException` ci-dessus ; après la correction ils passent.
 */
class PanneauComparaisonPorteTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    /** Installs a licence source, runs the block, then restores the default one. */
    private fun avecLicence(source: SourceLicence, bloc: () -> Unit) {
        ProGate.installerSource(source)
        try {
            bloc()
        } finally {
            ProGate.installerSource(SourceLicenceAbsent)
        }
    }

    /** A machine with the product licensed. */
    private object LicenceActive : SourceLicence {
        override fun lire() = LectureLicence(facadeInitialisee = true, sceau = "key:essai")
    }

    /** A machine with NO licence for the product (the free user's case). */
    private object LicenceAbsente : SourceLicence {
        override fun lire() = LectureLicence(facadeInitialisee = true, sceau = null)
    }

    /**
     * The IDE is still starting: the platform licence facade is NOT
     * initialised yet, so the answer is INCONNU (never a licence).
     */
    private object LicencePasLisible : SourceLicence {
        override fun lire() = LectureLicence(
            facadeInitialisee = false,
            sceau = null,
            motif = "licence facade not initialised yet"
        )
    }

    // ------------------------------------------------------------------
    // P0 - the panel must build (this is the RED of the counter-proof)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P0 - the Compare panel builds without exception: the CardLayout gate is correctly parented")
    fun lePanneauSeConstruitSansException() {
        val panneau = PanneauComparaison(ComparaisonHub())
        assertNotNull(panneau.rootComponent, "the panel must expose its Swing component")
    }

    @Test
    @DisplayName("P0 bis - the gate really holds its 2 cards (content and lock)")
    fun laPorteContientSesDeuxCartes() {
        val panneau = PanneauComparaison(ComparaisonHub())
        val porte = porteDe(panneau)
        assertEquals(2, porte.componentCount, "the gate must hold exactly the 2 cards")
        assertTrue(
            porte.components.any { contientUnTextArea(it) },
            "one card must hold the lock message (a JTextArea)"
        )
    }

    // ------------------------------------------------------------------
    // P1 - free user: the LOCK card is visible, with the expected message
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P1 - free user (no licence): the lock card is visible with \"No licence is active on this machine.\"")
    fun utilisateurGratuitVoitLaPorteFermee() {
        avecLicence(LicenceAbsente) {
            val panneau = PanneauComparaison(ComparaisonHub())
            val porte = porteDe(panneau)
            val visible = carteVisible(porte)
            assertTrue(
                contientUnTextArea(visible),
                "the visible card must be the lock card (it holds the message)"
            )
            val message = (visible as JPanel).components
                .filterIsInstance<JTextArea>()
                .firstOrNull()
            assertNotNull(message, "the lock card must hold the message area")
            assertTrue(
                message!!.text.contains("No licence is active on this machine."),
                "the free user must read \"No licence is active on this machine.\", got: ${message.text}"
            )
        }
    }

    // ------------------------------------------------------------------
    // P2 - licensed user: the CONTENT card is visible
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P2 - licensed user: the content card is visible (the lock card is hidden)")
    fun utilisateurLicenceVoitLeContenu() {
        avecLicence(LicenceActive) {
            val panneau = PanneauComparaison(ComparaisonHub())
            val porte = porteDe(panneau)
            val visible = carteVisible(porte)
            assertFalse(
                contientUnTextArea(visible),
                "with an active licence the content card must be the visible one"
            )
        }
    }

    // ------------------------------------------------------------------
    // P3 - the licence is read AGAIN when the tab becomes visible again
    //      (jury 11/11, point 2 of the review)
    // ------------------------------------------------------------------

    /** Waits (up to [delaiMs]) until the gate shows the CONTENT card. */
    private fun attendreDeverrouillage(porte: JPanel, delaiMs: Long = 3000): Boolean {
        val fin = System.currentTimeMillis() + delaiMs
        while (System.currentTimeMillis() < fin) {
            if (!contientUnTextArea(carteVisible(porte))) return true
            Thread.sleep(25)
        }
        return false
    }

    /** Waits (up to [delaiMs]) until the gate shows the LOCK card. */
    private fun attendreVerrouillage(porte: JPanel, delaiMs: Long = 3000): Boolean {
        val fin = System.currentTimeMillis() + delaiMs
        while (System.currentTimeMillis() < fin) {
            if (contientUnTextArea(carteVisible(porte))) return true
            Thread.sleep(25)
        }
        return false
    }

    @Test
    @DisplayName("P3 - licence absent at startup then active: the tab unlocks when it becomes visible again")
    fun licenceActiveeApresDemarrageDeverrouille() {
        // The platform only fires SHOWING_CHANGED on a component that is
        // really showing, which needs a display (Xvfb). Without a display
        // this test is SKIPPED (never a false green): the suite stays green
        // everywhere and the proof is done where a display exists.
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "a display is required (DISPLAY set): SHOWING_CHANGED only happens on a visible window"
        )
        avecLicence(LicencePasLisible) {
            // 1. at startup the licence facade is not initialised yet
            //    -> INCONNU -> the tab is locked.
            val panneau = PanneauComparaison(ComparaisonHub())
            val porte = porteDe(panneau)
            assertTrue(
                contientUnTextArea(carteVisible(porte)),
                "without a readable licence the lock card must be the visible one"
            )

            val frame = JFrame("PanneauComparaisonPorteTest")
            try {
                frame.add(panneau.rootComponent)
                frame.setSize(320, 240)
                // 2. the tab becomes visible for the first time: the platform
                //    fires SHOWING_CHANGED, but the licence is STILL not
                //    readable -> the tab must stay locked.
                frame.isVisible = true
                Thread.sleep(200)
                assertTrue(
                    contientUnTextArea(carteVisible(porte)),
                    "the tab must stay locked while the licence cannot be read"
                )

                // 3. the licence becomes readable (the IDE finished starting,
                //    or the purchase completed): the source now answers PRO.
                ProGate.installerSource(LicenceActive)

                // 4. the tab becomes visible again -> the licence must be read
                //    again -> the tab is unlocked.
                frame.isVisible = false
                Thread.sleep(100)
                frame.isVisible = true
                assertTrue(
                    attendreDeverrouillage(porte),
                    "the licence must be read again when the tab becomes visible again"
                )

                // 5. the same, with the event dispatched explicitly
                //    (HIERARCHY_CHANGED id + SHOWING_CHANGED flags): the
                //    licence can no longer be read, so the tab must lock again.
                ProGate.installerSource(LicencePasLisible)
                Thread.sleep(100)
                panneau.rootComponent.dispatchEvent(
                    HierarchyEvent(
                        panneau.rootComponent,
                        HierarchyEvent.HIERARCHY_CHANGED,
                        panneau.rootComponent,
                        frame.contentPane,
                        HierarchyEvent.SHOWING_CHANGED.toLong()
                    )
                )
                assertTrue(
                    attendreVerrouillage(porte),
                    "an explicit SHOWING_CHANGED event must also trigger a new licence reading"
                )
            } finally {
                frame.dispose()
            }
        }
    }
}
