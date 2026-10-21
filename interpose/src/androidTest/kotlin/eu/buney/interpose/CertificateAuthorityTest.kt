package eu.buney.interpose

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The CA is the part a user has to handle themselves, so the round trip through PEM — the
 * step where they copy one half into `res/raw` and the other into test assets — is worth
 * pinning.
 */
@RunWith(AndroidJUnit4::class)
class CertificateAuthorityTest {

    @Test
    fun aGeneratedAuthorityCanSignForAnyHost() {
        val ca = CertificateAuthority.generate()

        val certificates = ca.forHost("api.example.com")
        val leaf = certificates.keyManager.getCertificateChain("private").first()

        assertTrue(
            "The leaf should name the host it was minted for: ${leaf.subjectX500Principal}",
            leaf.toString().contains("api.example.com"),
        )
    }

    @Test
    fun theSameHostIsNotSignedTwice() {
        val ca = CertificateAuthority.generate()

        // Minting a key pair is slow enough on an emulator to matter across a test run.
        assertTrue(
            "forHost should cache per host",
            ca.forHost("api.example.com") === ca.forHost("api.example.com"),
        )
    }

    @Test
    fun pemSurvivesTheRoundTripUsersAreToldToMake() {
        val original = CertificateAuthority.generate(commonName = "Round Trip CA")
        val pem = original.toPem()

        // The documented order: private key first, then the certificate, nothing else.
        assertTrue("Missing private key block: $pem", pem.contains("-----BEGIN PRIVATE KEY-----"))
        assertTrue("Missing certificate block", pem.contains("-----BEGIN CERTIFICATE-----"))
        assertTrue(
            "Private key must come first for HeldCertificate.decode",
            pem.indexOf("BEGIN PRIVATE KEY") < pem.indexOf("BEGIN CERTIFICATE"),
        )

        val reloaded = CertificateAuthority.fromPem(pem)
        assertEquals(original.certificate(), reloaded.certificate())
    }

    @Test
    fun aGeneratedAuthorityIsActuallyAnAuthority() {
        // basicConstraints CA:TRUE — without it nothing it signs would be accepted.
        val ca = CertificateAuthority.generate()
        assertTrue(
            "The certificate must be marked as a CA",
            ca.certificate().basicConstraints >= 0,
        )
    }
}
