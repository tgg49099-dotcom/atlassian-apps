package com.slot5.mlexp.pro

import java.util.Locale

/**
 * Pure core of the comparison of 2 runs - Pro tier 1.
 *
 * v0.0.22. **No IntelliJ dependency here**: this file is the only place where
 * *what a comparison is* is decided. It is therefore testable in a bare JVM
 * (8 of the tests of tier 1 live here), and the Swing interface
 * (`PanneauComparaison.kt`) only displays it.
 *
 * The model is **agnostic of the source**: a [RunRef] carries its
 * [origine] (the URI of the server), its parameters and its metrics. Tier 1
 * only compares **MLflow** runs (plan section 2.2: "it does not compare
 * Optuna runs, W&B sweeps nor models"), but adding a source later will only
 * require another producer of [RunRef].
 *
 * Number format: French decimal separator (`0,86`), like the rest of the
 * plugin interface.
 */

/** State of one comparison row. */
enum class EtatComparaison(val libelle: String) {
    /** The same value on both sides. */
    IDENTIQUE("identical"),

    /** Both sides exist but differ. */
    DIFFERENT("different"),

    /** The key only exists in run B. */
    ABSENT_A("absent from A"),

    /** The key only exists in run A. */
    ABSENT_B("absent from B")
}

/** Reasons for a refusal - one state, one precise reason (discipline of the `licence/` module). */
enum class CodeRefus {
    /** No Pro licence: the gate is closed. */
    BLOQUE_SANS_LICENCE,

    /** The free trial has ended. */
    LICENCE_EXPIREE,

    /** The platform cannot conclude (facade not initialised). */
    FACADE_NON_INITIALISEE,

    /** The 2 runs come from 2 different servers. */
    ORIGINES_DIFFERENTES,

    /** More than 2 runs requested: the scope is exactly 2. */
    TROIS_RUNS_NON_SUPPORTES,

    /** Fewer than 2 runs requested. */
    DEUX_RUNS_REQUIS,

    /** One of the 2 runs no longer exists on the server. */
    RUN_INTROUVABLE
}

/**
 * A run, as seen by the comparison.
 *
 * @param origine the URI of the MLflow server the run comes from: two runs
 *        from different sources are **not** comparable (refusal
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
    /** The readable name if there is one, else the first 8 characters of the id. */
    fun libelle(): String = nom.ifEmpty { id.take(8) }
}

/** One row of the parameter table. */
data class LigneParam(
    val cle: String,
    val valeurA: String?,
    val valeurB: String?,
    val etat: EtatComparaison
)

/** One row of the metric table. */
data class LigneMetrique(
    val cle: String,
    val valeurA: Double?,
    val valeurB: Double?,
    /** Absolute gap (B - A); `null` if one of the two values is missing. */
    val ecartAbsolu: Double?,
    /** Relative gap in %; `null` if the reference value (A) is zero or absent. */
    val ecartRelatifPct: Double?,
    val etat: EtatComparaison
)

/** Result of a comparison request: either a reasoned refusal, or the comparison. */
sealed class ResultatComparaison {

    /** Refusal: the gate is closed, or the request is out of scope. */
    data class Refus(val code: CodeRefus, val message: String) : ResultatComparaison()

    /** The computed comparison. */
    data class Comparaison(
        val a: RunRef,
        val b: RunRef,
        val params: List<LigneParam>,
        val metriques: List<LigneMetrique>,
        val dureeA: String,
        val dureeB: String,
        /** Number of rows that differ (params + metrics). */
        val nombreEcarts: Int
    ) : ResultatComparaison() {
        /** The parameter rows that differ. */
        val ecartsParams: List<LigneParam> get() = params.filter { it.etat != EtatComparaison.IDENTIQUE }

        /** The metric rows that differ. */
        val ecartsMetriques: List<LigneMetrique> get() = metriques.filter { it.etat != EtatComparaison.IDENTIQUE }

        /** The metric keys common to the 2 runs (for the metric selector). */
        val metriquesCommunes: List<String>
            get() = metriques.filter { it.valeurA != null && it.valeurB != null }.map { it.cle }

        /**
         * The comparison in **Markdown** format (2 tables): this is what the
         * "Copy" button puts in the clipboard. No file, no CSV: the CSV/XLSX
         * export is **tier 2**.
         */
        fun markdown(): String {
            val sb = StringBuilder()
            sb.append("| parameter | A | B | state |\n")
            sb.append("|---|---|---|---|\n")
            for (l in params) {
                sb.append("| ${l.cle} | ${l.valeurA ?: "-"} | ${l.valeurB ?: "-"} | ${l.etat.libelle} |\n")
            }
            sb.append("\n")
            sb.append("| metric | A | B | absolute gap | relative gap | state |\n")
            sb.append("|---|---|---|---|---|---|\n")
            for (l in metriques) {
                sb.append("| ${l.cle} | ${ComparaisonRuns.formatNombre(l.valeurA) ?: "-"} | ${ComparaisonRuns.formatNombre(l.valeurB) ?: "-"} | ")
                sb.append("${ComparaisonRuns.formatNombre(l.ecartAbsolu) ?: "-"} | ${ComparaisonRuns.formatEcartRelatif(l.ecartRelatifPct) ?: "-"} | ${l.etat.libelle} |\n")
            }
            return sb.toString()
        }
    }
}

/**
 * The core of the comparison.
 *
 * Order of the refusals (deliberate): **licence first** (nothing is computed
 * without a licence), then the number of runs, then the origins.
 */
object ComparaisonRuns {

    /** Number of runs that tier 1 knows how to compare. */
    const val NOMBRE_RUNS = 2

    /**
     * Compares 2 runs.
     *
     * @param runs the runs to compare - exactly [NOMBRE_RUNS].
     * @param etat the state of the Pro gate.
     */
    fun calculer(runs: List<RunRef>, etat: ProState): ResultatComparaison {
        val codeLicence = codeRefusPourEtat(etat)
        if (codeLicence != null) {
            return ResultatComparaison.Refus(codeLicence, messageRefus(codeLicence))
        }
        if (runs.size < NOMBRE_RUNS) {
            return ResultatComparaison.Refus(
                CodeRefus.DEUX_RUNS_REQUIS,
                "Choose exactly 2 runs to compare (${runs.size} chosen)."
            )
        }
        if (runs.size > NOMBRE_RUNS) {
            return ResultatComparaison.Refus(
                CodeRefus.TROIS_RUNS_NON_SUPPORTES,
                "Pro tier 1 compares exactly 2 runs (${runs.size} requested): " +
                    "comparing 3 runs or more is not supported."
            )
        }
        val a = runs[0]
        val b = runs[1]
        if (a.id.isBlank() || b.id.isBlank()) {
            return ResultatComparaison.Refus(
                CodeRefus.RUN_INTROUVABLE,
                "One of the 2 runs no longer exists on the server: reload the list of runs."
            )
        }
        if (a.origine.isNotBlank() && b.origine.isNotBlank() && a.origine != b.origine) {
            return ResultatComparaison.Refus(
                CodeRefus.ORIGINES_DIFFERENTES,
                "The 2 runs come from 2 different servers (${a.origine} and ${b.origine}): " +
                    "they cannot be compared."
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

    /** Parameter table: union of the keys, alphabetical order. */
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

    /** Metric table: union of the keys, alphabetical order. */
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
            // The relative gap is computed on the reference value A; if A is 0,
            // the relative gap makes no sense ("-").
            val ecartRelatif = if (va != null && vb != null && va != 0.0) {
                (vb - va) / kotlin.math.abs(va) * 100.0
            } else {
                null
            }
            LigneMetrique(cle, va, vb, ecartAbsolu, ecartRelatif, etat)
        }
    }

    /** The refusal code matching a closed licence state. */
    fun codeRefusPourEtat(etat: ProState): CodeRefus? = when (etat) {
        ProState.PRO, ProState.ESSAI -> null
        ProState.EXPIRE -> CodeRefus.LICENCE_EXPIREE
        ProState.ABSENT -> CodeRefus.BLOQUE_SANS_LICENCE
        ProState.INCONNU -> CodeRefus.FACADE_NON_INITIALISEE
    }

    /** The user message of a refusal. */
    fun messageRefus(code: CodeRefus): String = when (code) {
        CodeRefus.BLOQUE_SANS_LICENCE ->
            "Comparing 2 runs is part of ML Experiment Cockpit Pro: " +
                "no licence is active on this machine."
        CodeRefus.LICENCE_EXPIREE ->
            "Your free trial of ML Experiment Cockpit Pro has ended."
        CodeRefus.FACADE_NON_INITIALISEE ->
            "Your ML Experiment Cockpit Pro licence cannot be checked right now."
        CodeRefus.ORIGINES_DIFFERENTES ->
            "The 2 runs come from 2 different servers: they cannot be compared."
        CodeRefus.TROIS_RUNS_NON_SUPPORTES ->
            "Pro tier 1 compares exactly 2 runs."
        CodeRefus.DEUX_RUNS_REQUIS ->
            "Choose exactly 2 runs to compare."
        CodeRefus.RUN_INTROUVABLE ->
            "One of the 2 runs no longer exists on the server."
    }

    /** Duration of a run: "-" if the start time is unknown, else end - start. */
    fun formatDuree(run: RunRef): String {
        if (run.startTimeMs <= 0L || run.endTimeMs <= 0L) return "-"
        val ms = run.endTimeMs - run.startTimeMs
        if (ms < 0) return "-"
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
     * A number in the French format; `null` if the value is missing.
     *
     * At most 6 decimals, trailing zeros **removed** (`0,8` and not
     * `0,800000`): that is what `%g` does not do in Java (it keeps the
     * trailing zeros). Extreme values switch to scientific notation so that a
     * non-zero number is never shown as "0".
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

    /** A relative gap in the French format: "+7,50 %". */
    fun formatEcartRelatif(v: Double?): String? {
        if (v == null) return null
        return String.format(Locale.FRANCE, "%+.2f %%", v)
    }
}
