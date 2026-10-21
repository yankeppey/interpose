package eu.buney.interpose

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The certificate authority [InterposeProxy] signs with, so it can present itself as any
 * host it intercepts.
 *
 * Interception means terminating TLS, which means answering as the host the app asked
 * for. That needs a certificate the app will accept, which needs a CA the app trusts.
 * Neither the JDK nor Android has a public API for signing an X.509 certificate
 * (`sun.security.x509.*` is absent, and Android's BouncyCastle is hidden), so this is
 * built on `okhttp-tls`.
 *
 * ## Getting the app to trust it
 *
 * Nothing is installed on the device and no user-store CA is involved. Instead the
 * certificate is a trust anchor in a **debug-only** network security config:
 *
 * ```xml
 * <!-- src/debug/res/xml/network_security_config.xml -->
 * <network-security-config>
 *     <debug-overrides>
 *         <trust-anchors>
 *             <certificates src="@raw/interpose_ca"/>
 *             <certificates src="system"/>
 *         </trust-anchors>
 *     </debug-overrides>
 * </network-security-config>
 * ```
 *
 * `debug-overrides` is ignored in a release build, so this cannot weaken a shipped app.
 *
 * The anchor has to be fixed at build time, which is why the usual shape is one CA
 * committed to the repository twice — the certificate in `src/debug/res/raw/` for the
 * app to trust, and the certificate *plus key* somewhere the test can read, usually
 * `src/androidTest/assets/`. Generate the pair once with [generate] and
 * [CertificateAuthority.toPem]:
 *
 * ```kotlin
 * // A throwaway test, run once; paste the two halves into the two files above.
 * println(CertificateAuthority.generate().toPem())
 * ```
 *
 * A test that drives its own HTTP client instead of the app's does not need any of
 * this — it can trust a freshly [generate]d CA directly, which is what Interpose's own
 * test suite does.
 */
public class CertificateAuthority private constructor(private val ca: HeldCertificate) {

    private val perHost = ConcurrentHashMap<String, HandshakeCertificates>()

    /** The anchor a client has to trust for anything this signs to be accepted. */
    public fun certificate(): X509Certificate = ca.certificate

    /**
     * Both halves as PEM — the private key first, then the certificate, which is the
     * order [fromPem] expects. Handy for minting a fixed CA once and committing it.
     */
    public fun toPem(): String = ca.privateKeyPkcs8Pem() + ca.certificatePem()

    /**
     * Certificates for presenting as [host], cached — generating a key pair costs real
     * time on an emulator and a test run revisits the same few hosts constantly.
     */
    internal fun forHost(host: String): HandshakeCertificates = perHost.getOrPut(host) {
        val leaf = HeldCertificate.Builder()
            .commonName(host)
            // The SAN is what gets validated, not the CN. okhttp-tls emits it as an IP
            // address or a DNS name depending on the shape of the string.
            .addSubjectAlternativeName(host)
            .signedBy(ca)
            .build()
        HandshakeCertificates.Builder()
            .heldCertificate(leaf, ca.certificate)
            .build()
    }

    public companion object {

        /**
         * Reads a CA from PEM holding exactly one `PRIVATE KEY` block and one
         * `CERTIFICATE` block, in that order and with nothing else — which is what
         * [toPem] produces, and what `HeldCertificate.decode` accepts.
         */
        @JvmStatic
        public fun fromPem(pem: String): CertificateAuthority =
            CertificateAuthority(HeldCertificate.decode(pem))

        /**
         * Mints a fresh CA. Use it directly from a test that drives its own client, or
         * once via [toPem] to produce a fixed CA to commit.
         *
         * RSA rather than the library default, because it is the key type every Android
         * version in the supported range validates without argument.
         */
        @JvmStatic
        @JvmOverloads
        public fun generate(
            commonName: String = "Interpose Test CA",
            validityDays: Long = DEFAULT_VALIDITY_DAYS,
        ): CertificateAuthority = CertificateAuthority(
            HeldCertificate.Builder()
                .certificateAuthority(0)
                .commonName(commonName)
                .rsa2048()
                .duration(validityDays, TimeUnit.DAYS)
                .build()
        )

        /** Twenty years: long enough that a committed CA never becomes the reason a test fails. */
        private const val DEFAULT_VALIDITY_DAYS = 7300L
    }
}
