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
 * reasons. Since 2026.1.7 the **message of the locked card is the same
 * honest sales message for every locked state** (it lives in
 * `PanneauComparaison.MESSAGE_VERROU_VENTE`): what the feature is, its price,
 * the trial (and how to start it: **in the IDE**), and where to buy. The
 * state itself is read on the badge of the tab, never in the message:
 *
 * | State | Meaning | Badge shown |
 * |---|---|---|
 * | [ProState.PRO] | active paid licence | "Pro: active" |
 * | [ProState.ESSAI] | free trial in progress | "Pro: active" |
 * | [ProState.EXPIRE] | the trial has ended | "Pro: not active - trial ended" |
 * | [ProState.ABSENT] | no licence for this product | "Pro: not active - no licence" |
 * | [ProState.INCONNU] | the platform cannot answer yet | "Pro: not verifiable yet" |
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
     * Checks the JetBrains signature of `key:` and `stamp:` stamps
     * (`VerificationSceau.kt`, port of the official sample `CheckLicense.java`).
     * Replaced only by the tests, never in production.
     */
    @Volatile
    private var verificateur: VerificateurSceau = VerificationJetBrains()

    /** For the tests only: installs another signature checker (see `VerificateurDeTest`). */
    fun installerVerificateur(v: VerificateurSceau) {
        verificateur = v
    }

    /** For the tests only: puts back the real JetBrains checker. */
    fun remettreVerificateurJetBrains() {
        verificateur = VerificationJetBrains()
    }

    /**
     * Turns a stamp into a state.
     *
     * Studio, 16/11/2026: `key:` and `stamp:` open the Pro tier **only if the
     * JetBrains signature is valid** (official sample `CheckLicense.java`).
     * Until 2026.1.3 the prefix alone was enough: a hand-written stamp
     * `key:anything` unlocked the paid tab. A stamp whose signature is not
     * valid means [ProState.ABSENT] (no licence), never an exception. The
     * trial stamp `eval:` carries no signature and is read as before. An
     * unknown prefix means [ProState.ABSENT]: **nothing else is a licence**.
     */
    fun etatDepuisSceau(sceau: String): ProState {
        if (sceau.startsWith(PREFIXE_CLE)) {
            return if (verificateur.cleValide(sceau.removePrefix(PREFIXE_CLE))) ProState.PRO else ProState.ABSENT
        }
        if (sceau.startsWith(PREFIXE_STAMP)) {
            return if (verificateur.serveurValide(sceau.removePrefix(PREFIXE_STAMP))) ProState.PRO else ProState.ABSENT
        }
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

    // 2026.1.7 (consigne of the mother, 19/11/2026): `messageVerrou(etat)` is
    // REMOVED. The locked card now shows ONE honest sales message, the same
    // for every locked state - it lives in the panel
    // (PanneauComparaison.MESSAGE_VERROU_VENTE): what the feature is, its
    // price, the trial (and how to start it, in the IDE), and a clickable
    // link to the Marketplace page (price and details). The
    // state (ABSENT, EXPIRE, INCONNU) stays on the badge of the tab.
}
