package com.slot5.mlexp.pro

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The JetBrains signature of the licence stamps (Studio, 16/11/2026).
 *
 * Reference: official sample CheckLicense.java (JetBrains/marketplace-makemecoffee-plugin,
 * commit be50246). The signed stamps come from a TEST root (sceaux-de-test.properties).
 */
class VerificationSceauTest {

    private val v = VerificateurDeTest.racineDeTest()
    private fun corps(nom: String) = VerificateurDeTest.sceau(nom).substringAfter(':')

    @Test
    @DisplayName("the two JetBrains roots of the official sample are read (JetProfile CA, License Servers CA)")
    fun lesDeuxRacinesJetBrainsSontLues() {
        assertEquals(2, VerificationJetBrains().nombreDeRacines)
        val noms = VerificationJetBrains.RACINES_JETBRAINS.map {
            (CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(it.toByteArray())) as X509Certificate)
                .subjectX500Principal.name
        }
        assertEquals(listOf("CN=JetProfile CA", "CN=License Servers CA"), noms)
    }

    @Test
    @DisplayName("key: a stamp signed by a trusted root opens Pro")
    fun cleSigneeValide() {
        assertTrue(v.cleValide(corps("cle_valide")))
        ProGate.installerVerificateur(v)
        try {
            assertEquals(ProState.PRO, ProGate.etatDepuisSceau(VerificateurDeTest.sceau("cle_valide")))
        } finally {
            ProGate.remettreVerificateurJetBrains()
        }
    }

    @Test
    @DisplayName("key: modified licence data, other licence id, 3 parts, garbage -> refused")
    fun cleFalsifiee() {
        assertFalse(v.cleValide(corps("cle_donnees_modifiees")), "data changed after signing")
        assertFalse(v.cleValide(corps("cle_autre_id")), "licenseId in front differs from the signed one")
        assertFalse(v.cleValide(corps("cle_trois_parties")), "3 parts instead of 4")
        assertFalse(v.cleValide("une-cle"), "hand-written stamp")
        assertFalse(v.cleValide("a-b-c-d"), "4 parts of garbage")
        assertFalse(v.cleValide(""), "empty")
    }

    @Test
    @DisplayName("key: a certificate that impersonates 'JetProfile CA' without being signed by the root -> refused")
    fun clePirate() {
        assertFalse(v.cleValide(corps("cle_pirate")))
        assertFalse(VerificationJetBrains().cleValide(corps("cle_pirate")))
    }

    @Test
    @DisplayName("key: a stamp valid for the test root is REFUSED by the real JetBrains roots")
    fun lesVraiesRacinesRefusentLaRacineDeTest() {
        assertFalse(VerificationJetBrains().cleValide(corps("cle_valide")))
        assertEquals(ProState.ABSENT, ProGate.etatDepuisSceau(VerificateurDeTest.sceau("cle_valide")))
        assertEquals(ProState.ABSENT, ProGate.etatDepuisSceau("key:une-cle"))
        assertEquals(ProState.ABSENT, ProGate.etatDepuisSceau("stamp:serveur-flottant"))
    }

    @Test
    @DisplayName("stamp: valid signature, same machine, fresh answer, intermediate certificate -> Pro")
    fun serveurValide() {
        assertTrue(v.serveurValide(corps("serveur_valide")))
        val t = VerificateurDeTest.instantServeur
        assertTrue(VerificateurDeTest.racineDeTest(t + 59 * 60_000L).serveurValide(corps("serveur_valide")), "59 min later")
    }

    @Test
    @DisplayName("stamp: old answer, other machine, expired certificate, no intermediate, impostor -> refused")
    fun serveurRefuse() {
        val t = VerificateurDeTest.instantServeur
        assertFalse(VerificateurDeTest.racineDeTest(t + 61 * 60_000L).serveurValide(corps("serveur_valide")), "61 min later")
        assertFalse(v.serveurValide(corps("serveur_autre_machine")))
        assertFalse(v.serveurValide(corps("serveur_cert_expire")))
        assertFalse(v.serveurValide(corps("serveur_sans_intermediaire")))
        assertFalse(v.serveurValide(corps("serveur_pirate")))
        assertFalse(v.serveurValide("serveur-flottant"))
        assertFalse(v.serveurValide("a:b:c:d:e:f"))
    }

    @Test
    @DisplayName("without any readable root, nothing opens (fail-closed)")
    fun sansRacineRienNOuvre() {
        val vide = VerificationJetBrains(listOf("not a certificate"))
        assertEquals(0, vide.nombreDeRacines)
        assertFalse(vide.cleValide(corps("cle_valide")))
    }

    @Test
    @DisplayName("the trial stamp eval: is read as before (no signature)")
    fun lEssaiNeChangePas() {
        val futur = System.currentTimeMillis() + 86_400_000L
        assertEquals(ProState.ESSAI, ProGate.etatDepuisSceau("eval:$futur"))
    }
}
