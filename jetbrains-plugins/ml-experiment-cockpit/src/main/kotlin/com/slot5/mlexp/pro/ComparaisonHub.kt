package com.slot5.mlexp.pro

import com.slot5.mlexp.Run

/**
 * Demande de comparaison, émise par l'onglet Experiments.
 *
 * v0.0.22. Clic droit sur un run → « Compare with… » : le run visé devient
 * **A**, et **B** se choisit dans la liste des runs de la même expérience.
 */
data class DemandeComparaison(
    val experimentId: String,
    val experimentNom: String,
    val runA: Run
)

/**
 * Le canal entre l'onglet **Experiments** (gratuit) et l'onglet **Compare**
 * (palier Pro n°1).
 *
 * Les deux panneaux vivent dans le même tool window mais n'ont aucune
 * référence l'un vers l'autre : ce petit relais évite que l'onglet gratuit
 * connaisse le panneau Pro (et inversement). Sans lui, il faudrait que le
 * panneau Experiments connaisse l'onglet Compare — c'est-à-dire que le
 * chemin gratuit dépende du code payant, ce que le test
 * `GratuitInchangeTest` interdit.
 */
class ComparaisonHub {

    @Volatile
    private var auditeur: ((DemandeComparaison) -> Unit)? = null

    @Volatile
    private var selecteur: (() -> Unit)? = null

    /** Le panneau Compare s'inscrit ici au démarrage. */
    fun ecouter(a: (DemandeComparaison) -> Unit) {
        auditeur = a
    }

    /**
     * La factory s'inscrit ici pour **afficher** l'onglet Compare quand
     * l'utilisateur demande une comparaison depuis l'onglet Experiments.
     */
    fun selectionner(s: () -> Unit) {
        selecteur = s
    }

    /** L'onglet Experiments demande une comparaison. */
    fun demander(d: DemandeComparaison) {
        selecteur?.invoke()
        auditeur?.invoke(d)
    }
}
