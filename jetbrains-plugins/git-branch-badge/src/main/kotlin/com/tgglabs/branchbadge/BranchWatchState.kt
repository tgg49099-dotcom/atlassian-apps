package com.tgglabs.branchbadge

/**
 * Detection d'un CHANGEMENT de branche Git.
 *
 * AUCUN import IntelliJ ici : cette classe est testable hors IDE (JUnit pur),
 * comme `GitHead`. C'est ce qui permet de jouer la preuve ROUGE -> VERT sur
 * la mecanique de rafraichissement.
 *
 * Pourquoi elle existe : le panneau « Project » ne redessine un noeud que
 * lorsqu'il est reconstruit. Le `projectViewNodeDecorator` n'est donc appele
 * qu'au (re)build de l'arbre. Sans mecanisme de rafraichissement, le nom de
 * branche reste fige sur la valeur affichee au premier build — c'est
 * exactement le defaut signale au cycle 62 (« le rafraichissement a chaud n'a
 * pas ete observe »).
 *
 * Le role de cette classe est minimal et sans ambiguite : dire si la branche
 * vient de CHANGER, donc si le panneau doit etre redessine. Elle ne connait
 * ni l'IDE, ni le systeme de fichiers : elle compare deux etats.
 */
class BranchWatchState {

    /** Derniere branche vue ; null = pas de depot, HEAD detache, ref distante. */
    private var lastBranch: String? = null

    /** Vrai apres la premiere observation (l'etat initial est memorise). */
    private var initialized: Boolean = false

    /** Derniere branche observee (null si aucune). */
    val knownBranch: String?
        get() = lastBranch

    /**
     * A observe [current] et indique si le panneau « Project » doit etre
     * rafraichi.
     *
     * - la PREMIERE observation memorise l'etat et ne declenche rien : le
     *   panneau vient d'etre construit, l'affichage est deja a jour ;
     * - une branche identique ne declenche rien (pas de rafraichissement
     *   inutile, le panneau n'est pas reconstruit en boucle) ;
     * - une branche differente declenche UN rafraichissement, y compris le
     *   passage a « aucune branche » (HEAD detache, depot supprime) ;
     * - le retour a une branche connue declenche aussi un rafraichissement.
     */
    fun branchChanged(current: String?): Boolean {
        if (!initialized) {
            initialized = true
            lastBranch = current
            return false
        }
        if (current == lastBranch) return false
        lastBranch = current
        return true
    }
}
