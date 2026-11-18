package com.slot5.mlexp.pro

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.security.cert.CertPathBuilder
import java.security.cert.CertPathValidator
import java.security.cert.CertStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Checks that a licence stamp was really signed by JetBrains.
 *
 * Studio, 16/11/2026. Before this file, the Pro gate opened on the **prefix
 * alone** (`key:` or `stamp:`): any text starting with `key:` unlocked the
 * paid tab. The official JetBrains sample (`CheckLicense.java`, repository
 * JetBrains/marketplace-makemecoffee-plugin, commit be50246 of 14/10/2025,
 * linked from the documentation "Add license verification calls to the
 * plugin code") verifies the signature of both stamps against the two public
 * JetBrains root certificates. This file is a line-by-line port of that
 * sample, with no IntelliJ class, so it runs in a bare JVM (`./gradlew test`).
 *
 * - `key:` (JetBrains account or activation code):
 *   `licenseId-licence-signature-certificate`, SHA1withRSA signature,
 *   certificate chained to a JetBrains root (validity checked at the
 *   certificate's start date, as in the sample: perpetual fallback licences
 *   keep working), and the `licenseId` written in the signed data must be the
 *   one in front of the stamp.
 * - `stamp:` (floating licence server):
 *   `expectedMachineId:timestamp:machineId:algorithm:signature:certificate[:intermediates]`,
 *   certificate chained to a JetBrains root and **valid today**, same
 *   machine, answer less than one hour old (the sample's value).
 *
 * Any error, any unexpected format: **false** (never an exception towards the
 * gate). The trial stamp `eval:` is not signed and is not handled here.
 */
interface VerificateurSceau {
    /** [corps] = the stamp without its `key:` prefix. */
    fun cleValide(corps: String): Boolean

    /** [corps] = the stamp without its `stamp:` prefix. */
    fun serveurValide(corps: String): Boolean
}

class VerificationJetBrains(
    racinesPem: List<String> = RACINES_JETBRAINS,
    private val maintenant: () -> Long = System::currentTimeMillis
) : VerificateurSceau {

    private val racines: Set<TrustAnchor> = racinesPem.mapNotNull { pem ->
        try {
            TrustAnchor(
                usine().generateCertificate(ByteArrayInputStream(pem.toByteArray(StandardCharsets.UTF_8))) as X509Certificate,
                null
            )
        } catch (t: Throwable) {
            null
        }
    }.toSet()

    /** Number of root certificates actually read (2 in production; checked by a test). */
    val nombreDeRacines: Int get() = racines.size

    override fun cleValide(corps: String): Boolean {
        val parties = corps.split("-")
        if (parties.size != 4) return false
        val (licenseId, licenceB64, signatureB64, certB64) = parties
        return try {
            val cert = certificat(b64(certB64), emptyList(), verifierAujourdHui = false) ?: return false
            val sig = Signature.getInstance("SHA1withRSA")
            sig.initVerify(cert)
            val donnees = b64(licenceB64)
            sig.update(donnees)
            if (!sig.verify(b64(signatureB64))) return false
            String(donnees, StandardCharsets.UTF_8).contains("\"licenseId\":\"$licenseId\"")
        } catch (t: Throwable) {
            false
        }
    }

    override fun serveurValide(corps: String): Boolean = try {
        val p = corps.split(":")
        if (p.size < 6) {
            false
        } else {
            val machineAttendue = p[0]
            val instant = p[1].toLong()
            val machine = p[2]
            val intermediaires = p.drop(6).map { b64(it) }
            val cert = certificat(b64(p[5]), intermediaires, verifierAujourdHui = true)
            if (cert == null) {
                false
            } else {
                val sig = Signature.getInstance(p[3])
                sig.initVerify(cert)
                sig.update("$instant:$machine".toByteArray(StandardCharsets.UTF_8))
                sig.verify(b64(p[4])) &&
                    machineAttendue == machine &&
                    Math.abs(maintenant() - instant) < VALIDITE_REPONSE_SERVEUR_MS
            }
        }
    } catch (t: Throwable) {
        false
    }

    /** The certificate if it chains to one of the roots, otherwise null. */
    private fun certificat(der: ByteArray, intermediaires: List<ByteArray>, verifierAujourdHui: Boolean): X509Certificate? {
        if (racines.isEmpty()) return null
        val usine = usine()
        val cert = usine.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        val tous = HashSet<Certificate>()
        tous.add(cert)
        intermediaires.forEach { tous.add(usine.generateCertificate(ByteArrayInputStream(it))) }
        return try {
            val selecteur = X509CertSelector()
            selecteur.certificate = cert
            val params = PKIXBuilderParameters(racines, selecteur)
            params.isRevocationEnabled = false
            if (!verifierAujourdHui) params.date = cert.notBefore
            params.addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(tous)))
            val chemin = CertPathBuilder.getInstance("PKIX").build(params).certPath
            CertPathValidator.getInstance("PKIX").validate(chemin, params)
            cert
        } catch (e: Exception) {
            null
        }
    }

    private fun b64(s: String): ByteArray = Base64.getMimeDecoder().decode(s.toByteArray(StandardCharsets.UTF_8))

    private fun usine(): CertificateFactory = CertificateFactory.getInstance("X.509")

    companion object {
        /** One hour, as in the official sample. */
        const val VALIDITE_REPONSE_SERVEUR_MS = 60L * 60L * 1000L

        /**
         * The two public JetBrains root certificates, copied from the official
         * sample (CheckLicense.java, ROOT_CERTIFICATES): "JetProfile CA"
         * (valid until 2045) and "License Servers CA" (valid until 2116).
         * Public certificates, not secrets.
         */
        val RACINES_JETBRAINS: List<String> = listOf(
            """
            -----BEGIN CERTIFICATE-----
            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
            -----END CERTIFICATE-----
            """.trimIndent(),
            """
            -----BEGIN CERTIFICATE-----
            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
            7tlDFnvaRnQukqSG+VqNWg==
            -----END CERTIFICATE-----
            """.trimIndent()
        )
    }
}
