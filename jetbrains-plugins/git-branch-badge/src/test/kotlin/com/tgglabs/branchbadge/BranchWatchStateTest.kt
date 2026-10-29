package com.tgglabs.branchbadge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests de la detection d'un changement de branche (`BranchWatchState`).
 *
 * La classe testee n'a AUCUNE dependance IntelliJ : elle se teste hors IDE.
 * C'est le mecanisme de rafraichissement du panneau « Project » qui etait
 * non verifie au cycle 62 (« le rafraichissement a chaud n'a pas ete
 * observe ») : ces tests fixent le comportement attendu.
 *
 * Chaque test correspond a un cas REEL :
 *  - premiere lecture : l'arbre du panneau vient d'etre construit, l'affichage
 *    est deja a jour, il ne faut pas le reconstruire une seconde fois ;
 *  - branche identique : aucun changement, aucun rafraichissement (sinon le
 *    panneau se reconstruit en boucle) ;
 *  - branche differente : le cas normal d'un `git checkout` ;
 *  - branche disparue : `git checkout <sha>` (HEAD detache) ou depot supprime,
 *    le nom de branche doit DISPARAITRE de l'affichage ;
 *  - retour de la branche : le cas `git checkout -b autre` puis retour ;
 *  - deux projets ouverts : leurs surveillances ne doivent pas se melanger.
 */
class BranchWatchStateTest {

    @Test
    @DisplayName("la premiere observation memorise l'etat et ne declenche rien")
    fun firstObservationDoesNotTrigger() {
        val state = BranchWatchState()
        assertFalse(state.branchChanged("main"))
        assertEquals("main", state.knownBranch)
    }

    @Test
    @DisplayName("une branche identique ne declenche aucun rafraichissement")
    fun sameBranchDoesNotTrigger() {
        val state = BranchWatchState()
        state.branchChanged("main")
        assertFalse(state.branchChanged("main"))
        assertFalse(state.branchChanged("main"))
    }

    @Test
    @DisplayName("un changement de branche declenche un rafraichissement")
    fun changedBranchTriggers() {
        val state = BranchWatchState()
        state.branchChanged("main")
        assertTrue(state.branchChanged("feature/nouvelle-fonction"))
        assertEquals("feature/nouvelle-fonction", state.knownBranch)
    }

    @Test
    @DisplayName("la disparition de la branche (HEAD detache) declenche un rafraichissement")
    fun missingBranchTriggers() {
        val state = BranchWatchState()
        state.branchChanged("main")
        assertTrue(state.branchChanged(null))
        assertEquals(null, state.knownBranch)
    }

    @Test
    @DisplayName("le retour de la branche declenche un nouveau rafraichissement")
    fun branchComingBackTriggers() {
        val state = BranchWatchState()
        state.branchChanged("main")
        assertTrue(state.branchChanged(null))
        assertTrue(state.branchChanged("main"))
    }

    @Test
    @DisplayName("des changements successifs sont tous detectes, une fois chacun")
    fun consecutiveChangesAreAllDetected() {
        val state = BranchWatchState()
        state.branchChanged("a")
        assertTrue(state.branchChanged("b"))
        assertFalse(state.branchChanged("b"))
        assertTrue(state.branchChanged("c"))
        assertEquals("c", state.knownBranch)
    }

    @Test
    @DisplayName("deux projets ouverts ont des surveillances independantes")
    fun statesAreIndependent() {
        val projet1 = BranchWatchState()
        val projet2 = BranchWatchState()
        projet1.branchChanged("main")
        projet2.branchChanged("develop")
        assertTrue(projet1.branchChanged("release"))
        assertFalse(projet2.branchChanged("develop"))
        assertEquals("release", projet1.knownBranch)
        assertEquals("develop", projet2.knownBranch)
    }
}
