package com.tgglabs.branchbadge

import com.intellij.ide.projectView.PresentationData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests du registre d'affichage (`BranchDisplay`).
 *
 * Cette classe manipule l'objet `PresentationData` du noeud racine — celui
 * que le panneau « Project » relit a chaque redessin. Les tests verifient
 * que le libelle ecrit est bien celui qui sera affiche, et surtout qu'un
 * etat disparu rend la main au libelle d'origine de l'IDE (le chemin du
 * projet) au lieu de laisser un texte vide.
 *
 * Elle est testee hors IDE : `PresentationData` est une classe simple du
 * plateau, sans dependance a un IDE ouvert.
 */
class BranchDisplayTest {

    /**
     * Reproduit l'ETAT DU NOEUD APRES LA DECORATION : l'IDE a ecrit son
     * libelle d'origine, le decorateur l'a remplace par « chemin [branche] »
     * et a enregistre le noeud. C'est exactement ce que fait
     * `BranchProjectViewDecorator.decorate`.
     */
    private fun decorated(
        basePath: String,
        originalLocation: String?,
        path: String?,
        status: String?
    ): PresentationData {
        val data = PresentationData()
        data.locationString = originalLocation
        BranchDisplay.onDecorated(basePath, data, originalLocation, path, status)
        data.locationString = BranchLabel.compose(path, status) ?: originalLocation
        return data
    }

    @Test
    @DisplayName("le libelle affiche le chemin ET la branche")
    fun writesPathAndBranchIntoPresentation() {
        val data = decorated("/proj", "/chemin/du/projet", "/chemin/du/projet", "main")
        assertEquals("/chemin/du/projet [main]", data.locationString)

        assertTrue(BranchDisplay.setStatus("/proj", "feature/x"))
        assertEquals("/chemin/du/projet [feature/x]", data.locationString)
        assertEquals("feature/x", BranchDisplay.displayedStatus("/proj"))
    }

    @Test
    @DisplayName("un etat qui disparait rend la main au libelle d'origine de l'IDE")
    fun restoresOriginalLocationWhenStatusDisappears() {
        val data = decorated("/proj2", "/chemin/du/projet", "/chemin/du/projet", "main")

        assertTrue(BranchDisplay.setStatus("/proj2", null))
        assertEquals("/chemin/du/projet", data.locationString)
    }

    @Test
    @DisplayName("ecrire le meme etat deux fois ne signale aucun changement")
    fun reportsNoChangeWhenStatusIsUnchanged() {
        val data = decorated("/proj3", "/chemin/du/projet", "/chemin/du/projet", "main")

        assertFalse(BranchDisplay.setStatus("/proj3", "main"))
        assertEquals("/chemin/du/projet [main]", data.locationString)
    }

    @Test
    @DisplayName("un projet inconnu n'est pas modifie")
    fun ignoresUnknownProject() {
        assertFalse(BranchDisplay.setStatus("/projet-inconnu", "main"))
    }

    @Test
    @DisplayName("apres reconstruction de l'arbre, le nouvel objet de presentation est utilise")
    fun usesLatestPresentationAfterRebuild() {
        val ancien = decorated("/proj4", "/chemin/du/projet", "/chemin/du/projet", "main")

        // L'IDE reconstruit le noeud avec un nouvel objet de presentation.
        val nouveau = decorated("/proj4", "/chemin/du/projet", "/chemin/du/projet", "main")

        assertTrue(BranchDisplay.setStatus("/proj4", "release/2026-10"))
        assertEquals("/chemin/du/projet [release/2026-10]", nouveau.locationString)
        assertEquals("/chemin/du/projet [main]", ancien.locationString)
    }

    @Test
    @DisplayName("deux projets ouverts sont independants")
    fun projectsAreIndependent() {
        val p1 = decorated("/p1", "/un", "/un", "main")
        val p2 = decorated("/p2", "/deux", "/deux", "main")

        assertTrue(BranchDisplay.setStatus("/p1", "dev"))
        assertEquals("/un [dev]", p1.locationString)
        assertEquals("/deux [main]", p2.locationString)
    }

    @Test
    @DisplayName("un HEAD detache est affiche comme « detached @ sha »")
    fun displaysDetachedState() {
        val data = decorated("/proj5", "/chemin/du/projet", "/chemin/du/projet", "main")

        assertTrue(BranchDisplay.setStatus("/proj5", "detached @ abc1234"))
        assertEquals("/chemin/du/projet [detached @ abc1234]", data.locationString)
        assertEquals("detached @ abc1234", BranchDisplay.displayedStatus("/proj5"))
    }

    @Test
    @DisplayName("quand l'IDE n'affichait aucun chemin, le chemin du projet est affiche")
    fun fallsBackToProjectPathWhenIdeShowsNoLocation() {
        // L'IDE n'affichait rien a cet endroit (locationString null).
        val data = decorated("/proj6", null, "/chemin/absolu", "main")
        assertEquals("/chemin/absolu [main]", data.locationString)

        // Reecrire le meme etat ne change rien (et ne le signale pas).
        assertFalse(BranchDisplay.setStatus("/proj6", "main"))
        assertEquals("/chemin/absolu [main]", data.locationString)
    }
}
