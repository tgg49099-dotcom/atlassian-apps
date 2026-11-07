package com.slot5.mlexp.pro

import com.slot5.mlexp.Run

/**
 * A comparison request, issued by the Experiments tab.
 *
 * v0.0.22. Right-click on a run -> "Compare with...": the targeted run becomes
 * **A**, and **B** is picked in the list of runs of the same experiment.
 */
data class DemandeComparaison(
    val experimentId: String,
    val experimentNom: String,
    val runA: Run
)

/**
 * The channel between the **Experiments** tab (free) and the **Compare** tab
 * (Pro tier 1).
 *
 * The two panels live in the same tool window but hold no reference to each
 * other: this small relay keeps the free tab free of any knowledge of the Pro
 * panel (and the other way round). Without it, the Experiments panel would
 * have to know about the Compare tab - in other words, the free path would
 * depend on the paid code, which the `GratuitInchangeTest` forbids.
 */
class ComparaisonHub {

    @Volatile
    private var auditeur: ((DemandeComparaison) -> Unit)? = null

    @Volatile
    private var selecteur: (() -> Unit)? = null

    /** The Compare panel registers itself here at startup. */
    fun ecouter(a: (DemandeComparaison) -> Unit) {
        auditeur = a
    }

    /**
     * The factory registers itself here to **show** the Compare tab when the
     * user asks for a comparison from the Experiments tab.
     */
    fun selectionner(s: () -> Unit) {
        selecteur = s
    }

    /** The Experiments tab asks for a comparison. */
    fun demander(d: DemandeComparaison) {
        selecteur?.invoke()
        auditeur?.invoke(d)
    }
}
