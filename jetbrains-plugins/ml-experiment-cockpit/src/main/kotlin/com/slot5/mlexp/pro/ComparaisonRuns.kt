package com.slot5.mlexp.pro

import java.util.Locale

/**
 * Cœur pur de la comparaison de 2 runs — palier Pro n°1.
 *
 * v0.0.22. **Aucune dépendance IntelliJ ici** : ce fichier est le seul
 * endroit où se décide *ce qu'est* une comparaison. Il est donc testable dans
 * un JVM nu (8 des tests du palier n°1 vivent ici), et l'interface Swing
 * (`PanneauComparaison.kt`) ne fait que l'afficher.
 *
 * Le modèle est **agnostique de la source** : un [RunRef] porte son
 * [origine] (l'URI du serveur), ses paramètres et ses métriques. Le palier
 * n°1 ne compare que des runs **MLflow** (plan § 2.2 : « ne compare pas des
 * runs Optuna, des sweeps W&B ni des modèles »), mais ajouter une source
 * plus tard ne demandera qu'un autre producteur de [RunRef].
 *
 * Format des nombres : séparateur décimal français (`0,86`), comme le reste
 * de l'interface du plugin.
 */

/** État d'une ligne de comparaison. */
enum class EtatComparaison(val libelle: String) {
    /** La même valeur des deux côtés. */
    IDENTIQUE("identique"),

    /** Les deux côtés existent mais diffèrent. */
    DIFFERENT("différent"),

    /** La clé n'existe que dans le run B. */
    ABSENT_A("absent de A"),

    /** La clé n'existe que dans le run A. */
    ABSENT_B("absent de B")
}

/** Motifs de refus — un état, un motif précis (discipline du module `licence/`). */
enum class CodeRefus {
    /** Aucune licence Pro : la porte est fermée. */
    BLOQUE_SANS_LICENCE,

    /** L'essai gratuit est terminé. */
    LICENCE_EXPIREE,

    /** La plateforme ne peut pas conclure (facade non initialisé). */
    FACADE_NON_INITIALISEE,

    /** Les 2 runs viennent de 2 serveurs différents. */
    ORIGINES_DIFFERENTES,

    /** Plus de 2 runs demandés : le périmètre est exactement 2. */
    TROIS_RUNS_NON_SUPPORTES,

    /** Moins de 2 runs demandés. */
    DEUX_RUNS_REQUIS,

    /** Un des 2 runs n'existe plus sur le serveur. */
    RUN_INTROUVABLE
}

/**
 * Un run, vu par la comparaison.
 *
 * @param origine l'URI du serveur MLflow d'où vient le run : deux runs
 *        d'origines différentes ne sont **pas** comparables (refus
 *        [CodeRefus.ORIGINES_DIFFERENTES]).
 */
data class RunRef(
    val id: String,
    val nom: String = "",
    val statut: String = "",
    val startTimeMs: Long = 0,
    val endTimeMs: Long = 0,
    val origine: String = "",
    val params: Map<String, String> = emptyMap(),
    val metriques: Map<String, Double> = emptyMap()
) {
    /** Le nom lisible s'il existe, sinon les 8 premiers caractères de l'identifiant. */
    fun libelle(): String = nom.ifEmpty { id.take(8) }
}

/** Une ligne du tableau des paramètres. */
data class LigneParam(
    val cle: String,
    val valeurA: String?,
    val valeurB: String?,
    val etat: EtatComparaison
)

/** Une ligne du tableau des métriques. */
data class LigneMetrique(
    val cle: String,
    val valeurA: Double?,
    val valeurB: Double?,
    /** Écart absolu (B − A) ; `null` si une des deux valeurs manque. */
    val ecartAbsolu: Double?,
    /** Écart relatif en % ; `null` si la valeur de référence (A) est nulle ou absente. */
    val ecartRelatifPct: Double?,
    val etat: EtatComparaison
)

/** Résultat d'une demande de comparaison : soit un refus motivé, soit la comparaison. */
sealed class ResultatComparaison {

    /** Refus : la porte est fermée, ou la demande est hors périmètre. */
    data class Refus(val code: CodeRefus, val message: String) : ResultatComparaison()

    /** Comparaison calculée. */
    data class Comparaison(
        val a: RunRef,
        val b: RunRef,
        val params: List<LigneParam>,
        val metriques: List<LigneMetrique>,
        val dureeA: String,
        val dureeB: String,
        /** Nombre de lignes qui diffèrent (params + métriques). */
        val nombreEcarts: Int
    ) : ResultatComparaison() {
        /** Lignes de paramètres qui diffèrent. */
        val ecartsParams: List<LigneParam> get() = params.filter { it.etat != EtatComparaison.IDENTIQUE }

        /** Lignes de métriques qui diffèrent. */
        val ecartsMetriques: List<LigneMetrique> get() = metriques.filter { it.etat != EtatComparaison.IDENTIQUE }

        /** Clés de métriques communes aux 2 runs (pour le sélecteur de métrique). */
        val metriquesCommunes: List<String>
            get() = metriques.filter { it.valeurA != null && it.valeurB != null }.map { it.cle }

        /**
         * La comparaison au format **Markdown** (2 tableaux) : c'est ce que le
         * bouton « Copy » met dans le presse-papier. Pas de fichier, pas de
         * CSV : l'export CSV/XLSX est le **palier n°2**.
         */
        fun markdown(): String {
            val sb = StringBuilder()
            sb.append("| paramètre | A | B | état |\n")
            sb.append("|---|---|---|---|\n")
            for (l in params) {
                sb.append("| ${l.cle} | ${l.valeurA ?: "—"} | ${l.valeurB ?: "—"} | ${l.etat.libelle} |\n")
            }
            sb.append("\n")
            sb.append("| métrique | A | B | écart absolu | écart relatif | état |\n")
            sb.append("|---|---|---|---|---|---|\n")
            for (l in metriques) {
                sb.append("| ${l.cle} | ${ComparaisonRuns.formatNombre(l.valeurA) ?: "—"} | ${ComparaisonRuns.formatNombre(l.valeurB) ?: "—"} | ")
                sb.append("${ComparaisonRuns.formatNombre(l.ecartAbsolu) ?: "—"} | ${ComparaisonRuns.formatEcartRelatif(l.ecartRelatifPct) ?: "—"} | ${l.etat.libelle} |\n")
            }
            return sb.toString()
        }
    }
}

/**
 * Le cœur de la comparaison.
 *
 * Ordre des refus (volontaire) : **licence d'abord** (rien n'est calculé
 * sans licence), puis le nombre de runs, puis les origines.
 */
object ComparaisonRuns {

    /** Nombre de runs que le palier n°1 sait comparer. */
    const val NOMBRE_RUNS = 2

    /**
     * Compare 2 runs.
     *
     * @param runs les runs à comparer — exactement [NOMBRE_RUNS].
     * @param etat l'état de la porte Pro.
     */
    fun calculer(runs: List<RunRef>, etat: ProState): ResultatComparaison {
        val codeLicence = codeRefusPourEtat(etat)
        if (codeLicence != null) {
            return ResultatComparaison.Refus(codeLicence, messageRefus(codeLicence))
        }
        if (runs.size < NOMBRE_RUNS) {
            return ResultatComparaison.Refus(
                CodeRefus.DEUX_RUNS_REQUIS,
                "Choisir exactement 2 runs a comparer (${runs.size} choisi)."
            )
        }
        if (runs.size > NOMBRE_RUNS) {
            return ResultatComparaison.Refus(
                CodeRefus.TROIS_RUNS_NON_SUPPORTES,
                "Le palier n°1 compare exactement 2 runs (${runs.size} demandés) : " +
                    "la comparaison de 3 runs ou plus n'est pas supportee."
            )
        }
        val a = runs[0]
        val b = runs[1]
        if (a.id.isBlank() || b.id.isBlank()) {
            return ResultatComparaison.Refus(
                CodeRefus.RUN_INTROUVABLE,
                "Un des 2 runs n'existe plus sur le serveur : rechargez la liste des runs."
            )
        }
        if (a.origine.isNotBlank() && b.origine.isNotBlank() && a.origine != b.origine) {
            return ResultatComparaison.Refus(
                CodeRefus.ORIGINES_DIFFERENTES,
                "Les 2 runs viennent de 2 serveurs differents (${a.origine} et ${b.origine}) : " +
                    "ils ne sont pas comparables."
            )
        }

        val lignesParam = lignesParams(a, b)
        val lignesMetrique = lignesMetriques(a, b)
        val ecarts = lignesParam.count { it.etat != EtatComparaison.IDENTIQUE } +
            lignesMetrique.count { it.etat != EtatComparaison.IDENTIQUE }

        return ResultatComparaison.Comparaison(
            a = a,
            b = b,
            params = lignesParam,
            metriques = lignesMetrique,
            dureeA = formatDuree(a),
            dureeB = formatDuree(b),
            nombreEcarts = ecarts
        )
    }

    /** Table des paramètres : union des clés, tri alphabétique. */
    fun lignesParams(a: RunRef, b: RunRef): List<LigneParam> {
        val cles = (a.params.keys + b.params.keys).sorted()
        return cles.map { cle ->
            val va = a.params[cle]
            val vb = b.params[cle]
            val etat = when {
                va == null -> EtatComparaison.ABSENT_A
                vb == null -> EtatComparaison.ABSENT_B
                va == vb -> EtatComparaison.IDENTIQUE
                else -> EtatComparaison.DIFFERENT
            }
            LigneParam(cle, va, vb, etat)
        }
    }

    /** Table des métriques : union des clés, tri alphabétique. */
    fun lignesMetriques(a: RunRef, b: RunRef): List<LigneMetrique> {
        val cles = (a.metriques.keys + b.metriques.keys).sorted()
        return cles.map { cle ->
            val va = a.metriques[cle]
            val vb = b.metriques[cle]
            val etat = when {
                va == null -> EtatComparaison.ABSENT_A
                vb == null -> EtatComparaison.ABSENT_B
                va == vb -> EtatComparaison.IDENTIQUE
                else -> EtatComparaison.DIFFERENT
            }
            val ecartAbsolu = if (va != null && vb != null) vb - va else null
            // L'écart relatif se calcule sur la valeur de référence A ; si A
            // vaut 0, l'écart relatif n'a pas de sens (« — »).
            val ecartRelatif = if (va != null && vb != null && va != 0.0) {
                (vb - va) / kotlin.math.abs(va) * 100.0
            } else {
                null
            }
            LigneMetrique(cle, va, vb, ecartAbsolu, ecartRelatif, etat)
        }
    }

    /** Le code de refus correspondant à un état de licence fermé. */
    fun codeRefusPourEtat(etat: ProState): CodeRefus? = when (etat) {
        ProState.PRO, ProState.ESSAI -> null
        ProState.EXPIRE -> CodeRefus.LICENCE_EXPIREE
        ProState.ABSENT -> CodeRefus.BLOQUE_SANS_LICENCE
        ProState.INCONNU -> CodeRefus.FACADE_NON_INITIALISEE
    }

    /** Le message utilisateur d'un refus. */
    fun messageRefus(code: CodeRefus): String = when (code) {
        CodeRefus.BLOQUE_SANS_LICENCE ->
            "La comparaison de 2 runs fait partie de ML Experiment Cockpit Pro : " +
                "aucune licence active sur cette machine."
        CodeRefus.LICENCE_EXPIREE ->
            "Votre essai gratuit de ML Experiment Cockpit Pro est termine."
        CodeRefus.FACADE_NON_INITIALISEE ->
            "Impossible de verifier votre licence ML Experiment Cockpit Pro pour le moment."
        CodeRefus.ORIGINES_DIFFERENTES ->
            "Les 2 runs viennent de 2 serveurs differents : ils ne sont pas comparables."
        CodeRefus.TROIS_RUNS_NON_SUPPORTES ->
            "Le palier n°1 compare exactement 2 runs."
        CodeRefus.DEUX_RUNS_REQUIS ->
            "Choisir exactement 2 runs a comparer."
        CodeRefus.RUN_INTROUVABLE ->
            "Un des 2 runs n'existe plus sur le serveur."
    }

    /** Durée d'un run : « — » si l'heure de début est inconnue, sinon fin − début. */
    fun formatDuree(run: RunRef): String {
        if (run.startTimeMs <= 0L || run.endTimeMs <= 0L) return "—"
        val ms = run.endTimeMs - run.startTimeMs
        if (ms < 0) return "—"
        return when {
            ms < 1000L -> "$ms ms"
            ms < 60_000L -> String.format(Locale.FRANCE, "%.1f s", ms / 1000.0)
            else -> {
                val total = ms / 1000
                val h = total / 3600
                val m = (total % 3600) / 60
                val s = total % 60
                if (h > 0) String.format(Locale.FRANCE, "%d h %02d min %02d s", h, m, s)
                else String.format(Locale.FRANCE, "%d min %02d s", m, s)
            }
        }
    }

    /**
     * Un nombre au format français ; `null` si la valeur manque.
     *
     * Au plus 6 décimales, zéros de fin **supprimés** (`0,8` et non
     * `0,800000`) : c'est ce que `%g` ne fait pas en Java (il garde les zéros
     * de fin). Les valeurs extrêmes passent en notation scientifique pour ne
     * pas afficher « 0 » pour un nombre non nul.
     */
    fun formatNombre(v: Double?): String? {
        if (v == null) return null
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
        val a = kotlin.math.abs(v)
        if (a != 0.0 && (a < 1e-6 || a >= 1e12)) {
            return String.format(Locale.FRANCE, "%.4e", v)
        }
        val fmt = java.text.DecimalFormat("0.######", java.text.DecimalFormatSymbols(Locale.FRANCE))
        return fmt.format(v)
    }

    /** Un écart relatif au format français : « +7,50 % ». */
    fun formatEcartRelatif(v: Double?): String? {
        if (v == null) return null
        return String.format(Locale.FRANCE, "%+.2f %%", v)
    }
}
