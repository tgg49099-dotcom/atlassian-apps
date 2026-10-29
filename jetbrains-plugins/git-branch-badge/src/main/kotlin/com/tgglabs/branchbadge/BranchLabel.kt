package com.tgglabs.branchbadge

/**
 * Composition du libelle affiche sur la racine du projet.
 *
 * AUCUN import IntelliJ ici : cette classe est testable hors IDE (JUnit pur),
 * comme `GitHead`. C'est ce qui permet de jouer la preuve ROUGE -> VERT sur la
 * forme du libelle.
 *
 * DEUX REGLELES, dictées par la revue du 27/10 :
 *
 *  1. Le libelle ne REMPLACE PAS le chemin du projet par le nom de branche :
 *     il affiche les DEUX, « chemin [branche] ». Un utilisateur qui veut voir
 *     le chemin ne le perd plus ; un utilisateur qui veut la branche la lit
 *     sans aller dans l'infobulle.
 *
 *  2. Un HEAD détaché (un `git checkout <sha>`) n'affiche plus rien du tout :
 *     c'est précisément le cas où l'utilisateur veut l'information. On
 *     affiche « detached @ abc1234 » (le SHA court).
 */
object BranchLabel {

    /** Préfixe d'un HEAD détaché. */
    const val DETACHED_PREFIX: String = "detached @ "

    /** Longueur du SHA affiché (7 caractères, comme `git log --oneline`). */
    const val SHORT_SHA_LENGTH: Int = 7

    /**
     * L'état affiché pour un projet donné.
     *
     * @param branch le nom de la branche locale, ou null
     * @param shortSha le SHA court d'un HEAD détaché, ou null
     * @return le texte affiché (« main », « detached @ abc1234 »), ou null
     *         quand le projet n'est dans aucun dépôt git (rien à afficher).
     */
    fun status(branch: String?, shortSha: String?): String? {
        if (branch != null) return branch
        if (shortSha == null) return null
        return DETACHED_PREFIX + shortSha
    }

    /**
     * Le libelle complet affiché sur la racine du projet.
     *
     * @param path le chemin du projet (ce que l'IDE affichait à cet endroit)
     * @param status l'état affiché (voir [status])
     * @return « chemin [branche] », ou null si rien à afficher.
     */
    fun compose(path: String?, status: String?): String? {
        if (status == null) return null
        val p = path?.trim()
        return if (p.isNullOrEmpty()) "[$status]" else "$p [$status]"
    }
}
