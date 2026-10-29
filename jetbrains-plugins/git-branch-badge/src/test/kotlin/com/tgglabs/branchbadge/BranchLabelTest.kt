package com.tgglabs.branchbadge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests de la composition du libelle (`BranchLabel`).
 *
 * La classe testee n'a AUCUNE dependance IntelliJ : elle se teste hors IDE.
 *
 * Ces tests fixent la REGLE demandee par la revue du 27/10 : le libelle
 * affiche « chemin [branche] » — il n'ECRASE plus le chemin du projet, il
 * affiche les deux — et un HEAD detache affiche « detached @ abc1234 » au
 * lieu de ne rien afficher du tout.
 */
class BranchLabelTest {

    @Test
    @DisplayName("le libelle affiche le chemin ET la branche")
    fun composesPathAndBranch() {
        assertEquals("/home/dev/mon-projet [main]", BranchLabel.compose("/home/dev/mon-projet", "main"))
    }

    @Test
    @DisplayName("le libelle affiche le chemin ET une branche avec des slashs")
    fun composesPathAndSlashBranch() {
        assertEquals(
            "/home/dev/mon-projet [feature/une-fonction]",
            BranchLabel.compose("/home/dev/mon-projet", "feature/une-fonction")
        )
    }

    @Test
    @DisplayName("sans chemin, le libelle est la branche entre crochets")
    fun composesBranchOnlyWhenPathIsMissing() {
        assertEquals("[main]", BranchLabel.compose(null, "main"))
        assertEquals("[main]", BranchLabel.compose("   ", "main"))
    }

    @Test
    @DisplayName("sans etat, il n'y a rien a afficher")
    fun composesNothingWithoutStatus() {
        assertNull(BranchLabel.compose("/home/dev/mon-projet", null))
    }

    @Test
    @DisplayName("un HEAD detache affiche « detached @ sha »")
    fun statusOfDetachedHead() {
        assertEquals("detached @ abc1234", BranchLabel.status(null, "abc1234"))
        assertEquals("/home/dev/mon-projet [detached @ abc1234]",
            BranchLabel.compose("/home/dev/mon-projet", BranchLabel.status(null, "abc1234")))
    }

    @Test
    @DisplayName("la branche locale prime sur le SHA detache")
    fun branchWinsOverDetachedSha() {
        assertEquals("main", BranchLabel.status("main", "abc1234"))
    }

    @Test
    @DisplayName("ni branche ni HEAD detache : rien a afficher")
    fun statusIsNullWithoutBranchNorDetachedHead() {
        assertNull(BranchLabel.status(null, null))
    }
}
