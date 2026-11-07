package com.slot5.mlexp.pro

/**
 * The Pro gate of tier 1 - "Compare 2 runs".
 *
 * v0.0.22. The paid tier 1 is sold **by JetBrains** (the *Freemium* model):
 * the platform holds the licence, not us. There is therefore **no key of ours,
 * no server and no cash handling**. Our work is limited to asking the platform
 * "does this machine have the product `PPCOCKPITPRO`?" and to turn the answer
 * into states.
 *
 * **Why this file depends on no IntelliJ class.** The gate has to be testable
 * in a bare JVM (`./gradlew test`, no IDE): the reading of the native facade
 * lives in `LicenceNatif.kt`, which implements [SourceLicence]. Here we only
 * know the *shape* of the answer.
 *
 * **The discipline taken from the `licence/` module** (plan section 3.3): one
 * state, one precise reason, never a vague exception. Five states, five
 * reasons:
 *
 * | State | Meaning | Message shown |
 * |---|---|---|
 * | [ProState.PRO] | active paid licence | - |
 * | [ProState.ESSAI] | free trial in progress | - |
 * | [ProState.EXPIRE] | the trial has ended | "your trial has ended" |
 * | [ProState.ABSENT] | no licence for this product | "no licence" |
 * | [ProState.INCONNU] | the platform cannot answer yet | "I cannot check" |
 *
 * Safety rule: **INCONNU never opens a paid feature** and **INCONNU never
 * closes a free feature**. The free path never consults this gate (the
 * `GratuitInchangeTest` proves it).
 */
enum class ProState {
    /** Active paid licence (JetBrains account, activation code, floating server). */
    PRO,

    /** Free trial in progress. */
    ESSAI,

    /** Trial ended (the `eval:` stamp carries an end date in the past). */
    EXPIRE,

    /** No licence for this product: the stamp is absent. */
    ABSENT,

    /**
     * The platform cannot conclude: the licence facade is not initialised yet
     * (the IDE is starting), or the class cannot be read. We never guess here.
     */
    INCONNU;

    /** True when the Pro features can be used. */
    fun autorise(): Boolean = this == PRO || this == ESSAI
}

/**
 * Result of a licence reading.
 *
 * @param facadeInitialisee false when the platform facade is not initialised
 *        yet: in that case [sceau] is meaningless.
 * @param sceau the confirmation stamp returned by the platform for the product
 *        [ProGate.CODE_PRODUIT]; `null` = no licence.
 * @param motif the precise reason of a failed read (never a vague
 *        exception); `null` when the read succeeded.
 */
data class LectureLicence(
    val facadeInitialisee: Boolean,
    val sceau: String? = null,
    val motif: String? = null
)

/** Source of the licence. Implementations: `LicenceNatif` (platform) and the test doubles. */
interface SourceLicence {
    fun lire(): LectureLicence
}

/** Default installed source: no licence is therefore ever granted (fail-closed). */
object SourceLicenceAbsent : SourceLicence {
    override fun lire(): LectureLicence = LectureLicence(
        facadeInitialisee = false,
        motif = "licence source not installed"
    )
}

/**
 * The Pro gate itself.
 *
 * The product code is written in **one single place** in the code
 * ([CODE_PRODUIT]); it is read in the `plugin.xml` descriptor by the
 * consistency test (`ProGateTest.leCodeProduitEstEcritAEcunEndroit`).
 */
object ProGate {

    /**
     * Product code of tier 1, registered in the "JetBrains Sales System".
     * It appears on the invoice and in the JetBrains accounts, and the
     * official documentation says it is "rather difficult to change": **it
     * never changes again**. Format: `P` + 3 to 14 UPPERCASE letters, without
     * any digit or symbol (documentation "add-required-parameters").
     */
    const val CODE_PRODUIT = "PPCOCKPITPRO"

    /** Licence obtained through the JetBrains account or an activation code. */
    const val PREFIXE_CLE = "key:"

    /** Licence obtained through a floating licence server. */
    const val PREFIXE_STAMP = "stamp:"

    /** Free trial; the stamp carries the end date of the trial. */
    const val PREFIXE_EVAL = "eval:"

    @Volatile
    private var source: SourceLicence = SourceLicenceAbsent

    /**
     * Installs the licence source (called at IDE startup by the tool window
     * factory). Without an installed source, the gate is
     * [ProState.INCONNU]: **fail-closed**.
     */
    fun installerSource(s: SourceLicence) {
        source = s
    }

    /** Current Pro state, read on every call (never cached: the licence may change). */
    fun etat(): ProState = etat(source.lire())

    /** Turns a licence reading into a state. */
    fun etat(l: LectureLicence): ProState = when {
        !l.facadeInitialisee -> ProState.INCONNU
        l.sceau.isNullOrBlank() -> ProState.ABSENT
        else -> etatDepuisSceau(l.sceau!!)
    }

    /**
     * Turns a stamp into a state.
     *
     * The three prefixes are read **as they are**, without interpretation: the
     * signature of the stamp is checked by the platform (it controls the
     * licence at IDE startup and at least once a day), we only note its
     * presence. An unknown prefix means [ProState.ABSENT]: **nothing else is a
     * licence**.
     */
    fun etatDepuisSceau(sceau: String): ProState {
        if (sceau.startsWith(PREFIXE_CLE)) return ProState.PRO
        if (sceau.startsWith(PREFIXE_STAMP)) return ProState.PRO
        if (sceau.startsWith(PREFIXE_EVAL)) {
            // The trial stamp carries the end date (an integer).
            val brut = sceau.removePrefix(PREFIXE_EVAL).substringBefore(':').trim().toLongOrNull()
                ?: return ProState.INCONNU
            val fin = enMillisecondes(brut)
            return if (fin > System.currentTimeMillis()) ProState.ESSAI else ProState.EXPIRE
        }
        return ProState.ABSENT
    }

    /**
     * Makes the reading of the trial stamp timestamp **independent of its
     * unit**.
     *
     * The platform does not document the unit of `expiration-date-long` (the
     * stamp is produced by the proprietary licence subsystem, absent from the
     * public source code): the same stamp can be expressed in
     * **milliseconds** or in **seconds**. Reading a stamp expressed in seconds
     * as milliseconds gives a date in 1970 -> the user in trial is told
     * "your trial has ended" and the Pro gate stays closed during the trial.
     *
     * The threshold separates the two units **without ambiguity**:
     * * `10^11` ms = March 1973; `10^11` s = the year 5138 => every plausible
     *   timestamp in **milliseconds** is `>= 10^11`, and every plausible
     *   timestamp in **seconds** is `< 10^11`.
     */
    private fun enMillisecondes(brut: Long): Long =
        if (brut >= SEUIL_MILLIORDES) brut else brut * 1000L

    /** `10^11`: above it the timestamp is in milliseconds; below it, in seconds. */
    private const val SEUIL_MILLIORDES = 100_000_000_000L

    /** Message shown to the user when the gate is closed. Never an error: a gate. */
    fun messageVerrou(etat: ProState): String = when (etat) {
        ProState.PRO -> "ML Experiment Cockpit Pro: active licence."
        ProState.ESSAI -> "ML Experiment Cockpit Pro: free trial in progress."
        ProState.EXPIRE ->
            "Your free trial of ML Experiment Cockpit Pro has ended. " +
                "Comparing 2 runs stays available with a paid licence."
        ProState.ABSENT ->
            "Comparing 2 runs is part of ML Experiment Cockpit Pro. " +
                "No licence is active on this machine."
        ProState.INCONNU ->
            "Your ML Experiment Cockpit Pro licence cannot be checked right now. " +
                "The free features stay available."
    }
}
