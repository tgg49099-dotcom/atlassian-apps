package com.tgglabs.branchbadge

import com.intellij.ide.projectView.PresentationData
import java.util.concurrent.ConcurrentHashMap

/**
 * Le libelle affiche sur la racine du projet.
 *
 * Le panneau « Project » ne redessine un noeud que lorsqu'il est reconstruit :
 * le decorateur (`projectViewNodeDecorator`) n'est appele qu'a ce moment-la.
 * Or reconstruire l'arbre est une operation lourde et, dans certaines versions
 * de la plateforme, l'appel a `ProjectView.refresh()` ne repasse pas par le
 * decorateur.
 *
 * Cette classe garde donc une reference vers l'objet `PresentationData` du
 * noeud racine — celui que le panneau lit a chaque redessin — ce qui permet
 * de **mettre a jour le libelle sans reconstruire l'arbre**.
 *
 * Elle ne contient aucun import de service : c'est un registre, pas une
 * fenetre. Les mutations de l'objet `PresentationData` doivent se faire sur
 * le thread UI (EDT), ce que garantit l'appelant.
 *
 * Le libelle ecrit est « chemin [branche] » (cf. [BranchLabel]) : le chemin du
 * projet n'est plus REMPLACE par le nom de branche (correction de la revue du
 * 27/10).
 */
object BranchDisplay {

    /** Ce qu'on retient d'un noeud racine deja decore. */
    private class Entry(
        @Volatile var presentation: PresentationData,
        /** Libelle affiche d'origine par l'IDE (le chemin du projet). */
        @Volatile var originalLocation: String?,
        /** Le chemin affiche devant la branche (« chemin [branche] »). */
        @Volatile var path: String?,
        /** Dernier etat ecrit dans le libelle (branche ou « detached @ sha »). */
        @Volatile var status: String?
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Appele par le decorateur a chaque construction de l'arbre : le noeud
     * racine vient d'etre decore, on retient son objet de presentation.
     *
     * @param basePath chemin de la racine du projet (cle du registre)
     * @param presentation l'objet de presentation du noeud
     * @param originalLocation le libelle affiche par l'IDE avant decoration
     * @param path le chemin a afficher devant la branche
     * @param status l'etat affiche (branche, « detached @ sha », ou null)
     */
    fun onDecorated(
        basePath: String,
        presentation: PresentationData,
        originalLocation: String?,
        path: String?,
        status: String?
    ) {
        val entry = entries[basePath]
        if (entry == null) {
            entries[basePath] = Entry(presentation, originalLocation, path, status)
            return
        }
        // L'IDE peut reconstruire le noeud avec un nouvel objet : on prend
        // toujours le plus recent, sinon la mutation ne se verrait pas.
        entry.presentation = presentation
        entry.originalLocation = originalLocation
        entry.path = path
        entry.status = status
    }

    /**
     * Ecrit [status] (« main », « detached @ abc1234 ») dans le libelle du
     * noeud racine de [basePath], sous la forme « chemin [etat] ».
     *
     * @return true si le libelle a ete modifie.
     */
    fun setStatus(basePath: String, status: String?): Boolean {
        val entry = entries[basePath] ?: return false
        val presentation = entry.presentation
        // Un etat disparu (depot supprime) : on rend la main au libelle
        // d'origine de l'IDE (le chemin du projet).
        val newLocation = if (status == null) entry.originalLocation
                          else BranchLabel.compose(entry.path, status)
        if (presentation.locationString == newLocation) return false
        presentation.locationString = newLocation
        entry.status = status
        return true
    }

    /** Etat actuellement ecrit dans le libelle (null si inconnue). */
    fun displayedStatus(basePath: String): String? = entries[basePath]?.status

    /** Oublie un projet (appelé a la fermeture). */
    fun forget(basePath: String) {
        entries.remove(basePath)
    }
}
