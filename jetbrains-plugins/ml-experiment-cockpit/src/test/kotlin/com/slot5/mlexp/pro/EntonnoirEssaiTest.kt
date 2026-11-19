package com.slot5.mlexp.pro

import java.awt.Component
import java.awt.Container
import java.io.File
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **L'ENTONNOIR D'ESSAI** — cycle 247 (consigne de la mère, 19/11/2026,
 * revue du Studio de 14:15).
 *
 * Le tableau de bord du vendeur disait **0 essai lancé, 0 vente** : le
 * bouton de la carte verrouillée disait « Get Pro », c'est-à-dire
 * « payer ». Le mot ne proposait donc **jamais l'essai gratuit**, et
 * l'essai se lance **dans l'IDE** (« Evaluate for free » dans la fenêtre
 * License Activation, ou Help | Register) — la page web du Marketplace ne
 * documente que l'achat.
 *
 * Ces tests prouvent les 5 points de la consigne :
 *
 *   - **E1 / E2** — sans licence (ABSENT) ou licence illisible (INCONNU) :
 *     le bouton dit « Start 30-day free trial » ;
 *   - **E3** — l'essai terminé (EXPIRE) : le bouton dit « Get Pro » (le
 *     paiement est alors le seul chemin) ;
 *   - **E4 / E5** — palier Pro actif (PRO, ESSAI) : **AUCUN bouton** dans le
 *     panneau (pas un bouton caché : aucun) ;
 *   - **E6 / E7** — les 2 messages qui refusent (carte verrouillée et
 *     message de refus) disent **comment** lancer l'essai ;
 *   - **E8** — le lien Marketplace est **conservé**, mais libellé « Pricing
 *     and details » : il porte le prix et les détails, il ne porte pas
 *     l'essai ;
 *   - **E9** — le bouton ouvre toujours le dialogue JetBrains du produit
 *     `PPCOCKPITPRO` (l'entonnoir ne se ferme pas) ;
 *   - **E10** — la ligne d'état **enroule** son message : l'instruction ne
 *     peut pas être coupée par la largeur de la fenêtre d'outils ;
 *   - **E11 / E12** — les sources ne promettent plus le paiement, ni
 *     l'essai « sur la page web ».
 */
class EntonnoirEssaiTest {

    @org.junit.jupiter.api.BeforeEach
    fun accepterLesSceauxDeTest() = ProGate.installerVerificateur(VerificateurDeTest.ACCEPTE)

    @org.junit.jupiter.api.AfterEach
    fun remettreLeVraiVerificateur() = ProGate.remettreVerificateurJetBrains()

    // ------------------------------------------------------------------
    // Les doubles (aucun secret, aucune licence, aucun jeton)
    // ------------------------------------------------------------------

    /** La lecture que ferait la plateforme JetBrains. */
    private fun lire(etat: ProState): SourceLicence = object : SourceLicence {
        override fun lire(): LectureLicence = when (etat) {
            ProState.PRO -> LectureLicence(facadeInitialisee = true, sceau = "key:test-key-no-secret")
            ProState.ESSAI ->
                LectureLicence(facadeInitialisee = true, sceau = "eval:" + (System.currentTimeMillis() + 86_400_000L))
            ProState.EXPIRE ->
                LectureLicence(facadeInitialisee = true, sceau = "eval:" + (System.currentTimeMillis() - 86_400_000L))
            ProState.ABSENT -> LectureLicence(facadeInitialisee = true, sceau = null)
            ProState.INCONNU -> LectureLicence(facadeInitialisee = false, sceau = null)
        }
    }

    /** Installe une lecture, construit le panneau, puis rend la main. */
    private fun panneauPour(etat: ProState): PanneauComparaison {
        ProGate.installerSource(lire(etat))
        return PanneauComparaison(ComparaisonHub())
    }

    // ------------------------------------------------------------------
    // Helpers (le panneau est lu, jamais deviné)
    // ------------------------------------------------------------------

    /** Every button of the panel (the whole tree, visible or not). */
    private fun boutonsDe(c: Component): List<JButton> {
        val liste = mutableListOf<JButton>()
        if (c is JButton) liste.add(c)
        if (c is Container) for (enfant in c.components) liste.addAll(boutonsDe(enfant))
        return liste
    }

    /** The button of the locked card (the licence button), by its label. */
    private fun boutonDe(panneau: PanneauComparaison, libelle: String): JButton? =
        boutonsDe(panneau.rootComponent).firstOrNull { it.text == libelle }

    /** Every JLabel text of the component (and of its descendants). */
    private fun textesDeLabel(c: Component): List<String> =
        listOfNotNull((c as? JLabel)?.text) +
            ((c as? Container)?.components?.flatMap { textesDeLabel(it) } ?: emptyList())

    /** The panel that HOLDS the CardLayout (the gate). */
    private fun porteDe(panneau: PanneauComparaison): JPanel =
        panneau.rootComponent.components
            .filterIsInstance<JPanel>()
            .first { it.layout is java.awt.CardLayout }

    /** The visible card of the gate. */
    private fun carteVisible(porte: JPanel): Component =
        porte.components.first { it.isVisible }

    /** The sales message of the locked card, as it is displayed. */
    private fun messageDeLaCarteVerrouillee(panneau: PanneauComparaison): String {
        val visible = carteVisible(porteDe(panneau))
        val zone = (visible as JPanel).components.filterIsInstance<JTextArea>().firstOrNull()
        assertNotNull(zone, "the locked card must hold its message area")
        return zone!!.text
    }

    // ------------------------------------------------------------------
    // E1 - ABSENT : le bouton propose l'essai, pas le paiement
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E1 - no licence (ABSENT): the button of the locked card says \"Start 30-day free trial\"")
    fun sansLicenceLeBoutonProposeLEssai() {
        val panneau = panneauPour(ProState.ABSENT)
        val bouton = boutonDe(panneau, "Start 30-day free trial")
        assertNotNull(
            bouton,
            "a customer without licence must find the button \"Start 30-day free trial\", " +
                "not a button that asks for a payment"
        )
        assertTrue(bouton!!.isVisible, "the trial button must be visible")
        assertNull(
            boutonDe(panneau, "Get Pro"),
            "the \"Get Pro\" button must NOT be offered when the trial is still available"
        )
    }

    // ------------------------------------------------------------------
    // E2 - INCONNU : la licence n'est pas encore lisible, l'essai est proposé
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E2 - licence not readable yet (INCONNU): the button still says \"Start 30-day free trial\"")
    fun licenceInconnueLeBoutonProposeLEssai() {
        val panneau = panneauPour(ProState.INCONNU)
        val bouton = boutonDe(panneau, "Start 30-day free trial")
        assertNotNull(
            bouton,
            "when the licence cannot be read yet, the button must offer the trial"
        )
        assertTrue(bouton!!.isVisible, "the trial button must be visible")
    }

    // ------------------------------------------------------------------
    // E3 - EXPIRE : l'essai est terminé, le paiement est proposé
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E3 - trial over (EXPIRE): the button says \"Get Pro\"")
    fun essaiTermineLeBoutonProposeLachat() {
        val panneau = panneauPour(ProState.EXPIRE)
        val bouton = boutonDe(panneau, "Get Pro")
        assertNotNull(
            bouton,
            "when the trial has ended, the purchase is the only way left: the button says \"Get Pro\""
        )
        assertTrue(bouton!!.isVisible, "the \"Get Pro\" button must be visible")
        assertNull(
            boutonDe(panneau, "Start 30-day free trial"),
            "the trial button must NOT be offered when the trial has ended"
        )
    }

    // ------------------------------------------------------------------
    // E4 / E5 - palier Pro actif : AUCUN bouton
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E4 - active licence (PRO): there is NO button left in the panel")
    fun licenceActiveAucunBouton() {
        val panneau = panneauPour(ProState.PRO)
        assertNull(
            boutonDe(panneau, "Start 30-day free trial"),
            "a customer who already has the Pro licence must not see a trial button"
        )
        assertNull(
            boutonDe(panneau, "Get Pro"),
            "a customer who already has the Pro licence must not see a purchase button"
        )
    }

    @Test
    @DisplayName("E5 - trial in progress (ESSAI): there is NO button left in the panel")
    fun essaiEnCoursAucunBouton() {
        val panneau = panneauPour(ProState.ESSAI)
        assertNull(
            boutonDe(panneau, "Start 30-day free trial"),
            "a customer in trial must not see a trial button"
        )
        assertNull(
            boutonDe(panneau, "Get Pro"),
            "a customer in trial must not see a purchase button"
        )
    }

    // ------------------------------------------------------------------
    // E6 - le message de la carte verrouillée dit COMMENT lancer l'essai
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E6 - the sales message of the locked card says how to start the trial (\"Evaluate for free\")")
    fun leMessageDitCommentLancerLEssai() {
        val panneau = panneauPour(ProState.ABSENT)
        val message = messageDeLaCarteVerrouillee(panneau)
        assertTrue(
            message.contains("Evaluate for free"),
            "the locked card must tell the customer to click \"Evaluate for free\" " +
                "in the JetBrains window, got: $message"
        )
        assertTrue(
            message.contains("Click \"Start 30-day free trial\", then \"Evaluate for free\" " +
                "in the JetBrains window (or Help | Register)."),
            "the locked card must carry the exact 4th line, got: $message"
        )
        assertTrue(
            message.contains("Start 30-day free trial"),
            "the locked card must name the button the customer has to click, got: $message"
        )
        // the 3 first lines are still there (what is sold, the price, the trial)
        assertTrue(message.contains("Compare is a Pro feature."), "got: $message")
        assertTrue(message.contains("29 EUR/year, VAT depending on your country."), "got: $message")
        assertTrue(message.contains("30-day free trial."), "got: $message")
        // and the message is 4 lines, not 3
        assertEquals(4, message.lines().size, "the sales message must have 4 lines, got: $message")
    }

    // ------------------------------------------------------------------
    // E7 - le message de refus dit comment lancer l'essai
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E7 - the refusal message says \"Start the 30-day free trial from the Compare tab.\"")
    fun leMessageDeRefusDitCommentLancerLEssai() {
        val message = ComparaisonRuns.messageRefus(CodeRefus.BLOQUE_SANS_LICENCE)
        assertTrue(
            message.contains("Start the 30-day free trial from the Compare tab."),
            "the refusal must say how to get a licence, got: $message"
        )
    }

    // ------------------------------------------------------------------
    // E8 - le lien Marketplace est conserve et libelle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E8 - the Marketplace link is kept, labelled \"Pricing and details\", and still clickable")
    fun leLienMarketplaceEstLibelle() {
        val panneau = panneauPour(ProState.ABSENT)
        val visible = carteVisible(porteDe(panneau))
        val lien = tousLesComposants(visible)
            .filterIsInstance<JLabel>()
            .firstOrNull { it.text == "Pricing and details" }
        assertNotNull(
            lien,
            "the locked card must offer the link \"Pricing and details\", got: " +
                textesDeLabel(visible)
        )
        assertTrue(
            lien!!.mouseListeners.isNotEmpty(),
            "the link must stay clickable (a mouse listener opens the browser)"
        )
        assertEquals(
            PanneauComparaison.URL_FICHE_MARKETPLACE,
            lien.toolTipText,
            "the link must still point to the Marketplace page of the plugin"
        )
    }

    /** Every component of the tree. */
    private fun tousLesComposants(c: Component): List<Component> {
        val liste = mutableListOf<Component>()
        liste.add(c)
        if (c is Container) for (enfant in c.components) liste.addAll(tousLesComposants(enfant))
        return liste
    }

    // ------------------------------------------------------------------
    // E9 - le bouton ouvre toujours le dialogue JetBrains du produit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E9 - the trial button still opens the JetBrains dialog for PPCOCKPITPRO")
    fun leBoutonOuvreLeDialogueJetBrains() {
        var codeOuvert: String? = null
        val acheteur = object : AcheteurPro {
            override fun ouvrir(codeProduit: String, message: String, surFermeture: (String?) -> Unit) {
                codeOuvert = codeProduit
                surFermeture(null)
            }
        }
        ProGate.installerSource(lire(ProState.ABSENT))
        try {
            val panneau = PanneauComparaison(ComparaisonHub(), acheteur)
            val bouton = boutonDe(panneau, "Start 30-day free trial")
            assertNotNull(bouton, "the trial button must be there")
            bouton!!.doClick(0)
            assertEquals(
                ProGate.CODE_PRODUIT,
                codeOuvert,
                "the dialog must be opened for our own product code (the trial dialog)"
            )
        } finally {
            ProGate.installerSource(SourceLicenceAbsent)
        }
    }

    // ------------------------------------------------------------------
    // E10 - la ligne d'etat enroule son message (il ne peut pas etre coupe)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E10 - the status line wraps its message: the trial instruction cannot be clipped")
    fun laLigneDEtatEnrouleSonMessage() {
        val panneau = panneauPour(ProState.ABSENT)
        val etiquette = tousLesComposants(panneau.rootComponent)
            .filterIsInstance<JLabel>()
            .firstOrNull { it.text.startsWith("<html>") }
        assertNotNull(
            etiquette,
            "the status line must be written in HTML: a plain JLabel clips the text " +
                "that is wider than the panel"
        )
        // The refusal message is displayed by the same method: it is written
        // in the label as it is (white box: the private method is called).
        val message = ComparaisonRuns.messageRefus(CodeRefus.BLOQUE_SANS_LICENCE)
        val methode = PanneauComparaison::class.java.getDeclaredMethod("status", String::class.java)
        methode.isAccessible = true
        methode.invoke(panneau, message)
        assertTrue(
            etiquette!!.text.contains(message),
            "the whole message must be in the label, got: ${etiquette.text}"
        )
        assertTrue(
            etiquette.text.contains("Start the 30-day free trial from the Compare tab."),
            "the whole message must be in the label, got: ${etiquette.text}"
        )
        // The message is LONGER than a narrow tool window: a plain label would
        // have cut it (this is the reason of the HTML).
        val largeur = etiquette.getFontMetrics(etiquette.font).stringWidth(message)
        assertTrue(
            largeur > 400,
            "the refusal message needs $largeur px: it must WRAP, not be cut"
        )
    }

    // ------------------------------------------------------------------
    // E11 - les sources ne promettent plus le paiement
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E11 - the sources offer the trial and not the payment")
    fun lesSourcesNOffrentPasLePaiement() {
        val panneau = File("src/main/kotlin/com/slot5/mlexp/pro/PanneauComparaison.kt")
        assertTrue(panneau.exists(), "the source of the panel must exist")
        val source = panneau.readText(Charsets.UTF_8)
        assertFalse(
            source.contains("JButton(\"Get Pro\")"),
            "the button must not be built with the label \"Get Pro\": it must be renamed " +
                "according to the licence state"
        )
        assertFalse(
            source.contains("where the buy and trial buttons"),
            "no comment may claim that the buy and trial buttons are on the Marketplace page: " +
                "the trial is started in the IDE"
        )
        assertEquals(
            "Start 30-day free trial",
            PanneauComparaison.LIBELLE_BOUTON_ESSAI,
            "the label of the trial button is the settled one"
        )
        assertEquals(
            "Get Pro",
            PanneauComparaison.LIBELLE_BOUTON_ACHAT,
            "\"Get Pro\" is kept only for the state EXPIRE"
        )
        assertEquals(
            "Pricing and details",
            PanneauComparaison.LIBELLE_LIEN_MARKETPLACE,
            "the link must be labelled with what it carries"
        )
    }

    // ------------------------------------------------------------------
    // E12 - les notes de version ne renvoient pas a la page web pour l'essai
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E12 - the release notes tell that the trial is started in the IDE")
    fun lesNotesDisentCommentLancerLEssai() {
        for (chemin in listOf(
            "src/main/resources/META-INF/plugin.xml",
            "build.gradle.kts"
        )) {
            val f = File(chemin)
            assertTrue(f.exists(), "the file $chemin must exist")
            val texte = f.readText(Charsets.UTF_8)
            val debut = texte.indexOf("<h3>2026.1.9 ")
            assertTrue(debut >= 0, "$chemin must describe the version 2026.1.9")
            val fin = texte.indexOf("<h3>", debut + 1)
            val bloc = texte.substring(debut, if (fin >= 0) fin else texte.length)
            assertTrue(
                bloc.contains("Evaluate for free"),
                "$chemin: the notes of 2026.1.9 must say that the trial is started " +
                    "in the IDE (\"Evaluate for free\")"
            )
            assertTrue(
                bloc.contains("Start 30-day free trial"),
                "$chemin: the notes of 2026.1.9 must name the button to click"
            )
            assertFalse(
                bloc.contains("where the trial and the buy buttons are"),
                "$chemin: the notes must not send the customer to the web page for the trial"
            )
            assertFalse(
                texte.contains("where the buy and trial buttons are"),
                "$chemin: no note may send the customer to the web page for the trial"
            )
        }
    }
}
