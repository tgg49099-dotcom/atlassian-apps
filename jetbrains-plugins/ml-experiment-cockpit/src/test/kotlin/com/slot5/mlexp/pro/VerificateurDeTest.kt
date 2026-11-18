package com.slot5.mlexp.pro

import java.util.Properties

/**
 * Test doubles for the stamp signature (Studio, 16/11/2026).
 *
 * The stamps of `sceaux-de-test.properties` were signed once with a TEST root
 * made by the Studio (no private key is stored, no real licence, no secret).
 */
object VerificateurDeTest {

    /** Accepts every `key:`/`stamp:` stamp: for the panel tests, which test the panel, not the signature. */
    val ACCEPTE: VerificateurSceau = object : VerificateurSceau {
        override fun cleValide(corps: String) = true
        override fun serveurValide(corps: String) = true
    }

    private val sceaux: Properties by lazy {
        val p = Properties()
        VerificateurDeTest::class.java.getResourceAsStream("sceaux-de-test.properties")!!.use { p.load(it) }
        p
    }

    /** One stamp of the file, by its name (for example `cle_valide`). */
    fun sceau(nom: String): String = sceaux.getProperty(nom) ?: error("unknown test stamp: $nom")

    /** Instant at which the floating-server test stamps were signed. */
    val instantServeur: Long get() = sceau("instant_serveur").toLong()

    /** The real checker, but trusting ONLY the test root; the clock is fixed on [maintenant]. */
    fun racineDeTest(maintenant: Long = instantServeur): VerificationJetBrains {
        val pem = "-----BEGIN CERTIFICATE-----\n" +
            sceau("racine_test").chunked(64).joinToString("\n") +
            "\n-----END CERTIFICATE-----\n"
        return VerificationJetBrains(listOf(pem)) { maintenant }
    }
}
