package com.slot5.mlexp.pro

/**
 * Porte Pro du palier n°1 — « Compare 2 runs ».
 *
 * v0.0.22. Le palier payant n°1 est vendu **par JetBrains** (modèle
 * *Freemium*) : c'est la plateforme qui détient la licence, pas nous. Il n'y
 * a donc **ni clé à nous, ni serveur, ni encaissement**. Notre travail se
 * limite à demander à la plateforme « cette machine a-t-elle le produit
 * `PPCOCKPITPRO` ? » et à traduire la réponse en états.
 *
 * **Pourquoi ce fichier ne dépend d'aucune classe IntelliJ.** La porte doit
 * être testable dans un JVM nu (`./gradlew test` sans IDE) : la lecture du
 * facade natif vit dans `LicenceNatif.kt`, qui implémente [SourceLicence].
 * Ici on ne connaît que la *forme* de la réponse.
 *
 * **La discipline reprise du module `licence/`** (plan § 3.3) : un état, un
 * motif précis, jamais d'exception vague. Cinq états, cinq motifs :
 *
 * | État | Signification | Motif affiché |
 * |---|---|---|
 * | [ProState.PRO] | licence payante active | — |
 * | [ProState.ESSAI] | essai gratuit en cours | — |
 * | [ProState.EXPIRE] | l'essai est terminé | « votre essai est terminé » |
 * | [ProState.ABSENT] | aucune licence pour ce produit | « aucune licence » |
 * | [ProState.INCONNU] | la plateforme ne peut pas encore répondre | « je ne peux pas vérifier » |
 *
 * Règle de sécurité : **INCONNU n'ouvre jamais une fonction payante** et
 * **INCONNU ne ferme jamais une fonction gratuite**. Le gratuit ne consulte
 * jamais cette porte (le test `GratuitInchangeTest` le prouve).
 */
enum class ProState {
    /** Licence payante active (compte JetBrains, code d'activation, serveur flottant). */
    PRO,

    /** Essai gratuit en cours. */
    ESSAI,

    /** Essai terminé (le sceau `eval:` porte une date de fin dépassée). */
    EXPIRE,

    /** Aucune licence pour ce produit : le sceau est absent. */
    ABSENT,

    /**
     * La plateforme ne peut pas conclure : le facade de licence n'est pas
     * encore initialisé (le IDE démarre), ou la classe n'est pas lisible.
     * On ne devine jamais dans ce cas.
     */
    INCONNU;

    /** Vrai si les fonctions Pro peuvent être utilisées. */
    fun autorise(): Boolean = this == PRO || this == ESSAI
}

/**
 * Résultat d'une lecture de licence.
 *
 * @param facadeInitialisee false quand le facade de la plateforme n'est pas
 *        encore initialisé : dans ce cas [sceau] est sans signification.
 * @param sceau le sceau de confirmation renvoyé par la plateforme pour le
 *        produit [ProGate.CODE_PRODUIT] ; `null` = pas de licence.
 * @param motif la raison précise d'un échec de lecture (jamais une
 *        exception vague) ; `null` quand la lecture a réussi.
 */
data class LectureLicence(
    val facadeInitialisee: Boolean,
    val sceau: String? = null,
    val motif: String? = null
)

/** Source de la licence. Implémentations : `LicenceNatif` (plateforme) et les doubles de test. */
interface SourceLicence {
    fun lire(): LectureLicence
}

/** Source installée par défaut : aucune licence n'est donc jamais concédée (fail-closed). */
object SourceLicenceAbsent : SourceLicence {
    override fun lire(): LectureLicence = LectureLicence(
        facadeInitialisee = false,
        motif = "source de licence non installee"
    )
}

/**
 * La porte Pro elle-même.
 *
 * Le code produit est écrit **à un seul endroit** dans le code ([CODE_PRODUIT]) ;
 * il est lu dans le descripteur `plugin.xml` par le test de cohérence
 * (`ProGateTest.leCodeProduitEstEcritAEcunEndroit`).
 */
object ProGate {

    /**
     * Code produit du palier n°1, enregistré dans le « JetBrains Sales
     * System ». Il apparaît sur la facture et dans les comptes JetBrains, et
     * la doc officielle le dit « rather difficult to change » : **il ne
     * change plus jamais**. Format : `P` + 3 à 14 MAJUSCULES, sans chiffre
     * ni symbole (doc « add-required-parameters »).
     */
    const val CODE_PRODUIT = "PPCOCKPITPRO"

    /** Licence obtenue via le compte JetBrains ou un code d'activation. */
    const val PREFIXE_CLE = "key:"

    /** Licence obtenue via un serveur de licences flottant. */
    const val PREFIXE_STAMP = "stamp:"

    /** Essai gratuit ; le sceau porte la date de fin de l'essai. */
    const val PREFIXE_EVAL = "eval:"

    @Volatile
    private var source: SourceLicence = SourceLicenceAbsent

    /**
     * Installe la source de licence (appelée au démarrage de l'IDE par la
     * factory du tool window). Sans source installée, la porte est
     * [ProState.INCONNU] : **fail-closed**.
     */
    fun installerSource(s: SourceLicence) {
        source = s
    }

    /** État Pro courant, lu à chaque appel (jamais mis en cache : la licence peut changer). */
    fun etat(): ProState = etat(source.lire())

    /** Traduit une lecture de licence en état. */
    fun etat(l: LectureLicence): ProState = when {
        !l.facadeInitialisee -> ProState.INCONNU
        l.sceau.isNullOrBlank() -> ProState.ABSENT
        else -> etatDepuisSceau(l.sceau!!)
    }

    /**
     * Traduit un sceau en état.
     *
     * Les trois préfixes sont lus **tels quels**, sans interprétation : la
     * signature du sceau est vérifiée par la plateforme (elle contrôle la
     * licence au démarrage de l'IDE et au moins une fois par jour), nous on
     * ne fait que constater sa présence. Un préfixe inconnu vaut
     * [ProState.ABSENT] : **rien d'autre n'est une licence**.
     */
    fun etatDepuisSceau(sceau: String): ProState {
        if (sceau.startsWith(PREFIXE_CLE)) return ProState.PRO
        if (sceau.startsWith(PREFIXE_STAMP)) return ProState.PRO
        if (sceau.startsWith(PREFIXE_EVAL)) {
            // Le sceau d'essai porte la date de fin (un entier).
            val brut = sceau.removePrefix(PREFIXE_EVAL).substringBefore(':').trim().toLongOrNull()
                ?: return ProState.INCONNU
            val fin = enMillisecondes(brut)
            return if (fin > System.currentTimeMillis()) ProState.ESSAI else ProState.EXPIRE
        }
        return ProState.ABSENT
    }

    /**
     * Rend la lecture de l'horodatage du sceau d'essai **indépendante de
     * l'unité**.
     *
     * La plateforme ne documente pas l'unité de `expiration-date-long` (le
     * sceau est produit par le sous-système de licence propriétaire, absent
     * du code source public) : le même sceau peut être exprimé en
     * **millisecondes** ou en **secondes**. Lire en millisecondes un sceau
     * exprimé en secondes donne une date de 1970 → l'utilisateur en essai se
     * voit annoncer « votre essai est terminé » et la porte Pro reste fermée
     * pendant l'essai.
     *
     * Le seuil sépare les deux unités **sans ambiguïté** :
     * * `10^11` ms = mars 1973 ; `10^11` s = an 5138 ⇒ tout horodatage
     *   plausible en **millisecondes** est `>= 10^11`, et tout horodatage
     *   plausible en **secondes** est `< 10^11`.
     */
    private fun enMillisecondes(brut: Long): Long =
        if (brut >= SEUIL_MILLIORDES) brut else brut * 1000L

    /** `10^11` : au-dessus, l'horodatage est en millisecondes ; en dessous, en secondes. */
    private const val SEUIL_MILLIORDES = 100_000_000_000L

    /** Message affiché à l'utilisateur quand la porte est fermée. Jamais une erreur : une porte. */
    fun messageVerrou(etat: ProState): String = when (etat) {
        ProState.PRO -> "ML Experiment Cockpit Pro : licence active."
        ProState.ESSAI -> "ML Experiment Cockpit Pro : essai gratuit en cours."
        ProState.EXPIRE ->
            "Votre essai gratuit de ML Experiment Cockpit Pro est terminé. " +
                "La comparaison de 2 runs reste accessible avec une licence payante."
        ProState.ABSENT ->
            "La comparaison de 2 runs fait partie de ML Experiment Cockpit Pro. " +
                "Aucune licence n'est active sur cette machine."
        ProState.INCONNU ->
            "Impossible de verifier votre licence ML Experiment Cockpit Pro pour le moment. " +
                "Les fonctions gratuites restent disponibles."
    }
}
