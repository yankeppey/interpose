package eu.buney.interpose.example

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import eu.buney.interpose.CertificateAuthority
import eu.buney.interpose.InterposeConfig
import eu.buney.interpose.InterposeRunner

/**
 * The hook that makes capture possible, and the place a real project puts its CA.
 *
 * Interception needs a certificate authority, which cannot travel in an `-e` argument,
 * so anything past "watch these hosts" is a subclass. Two details are worth copying:
 *
 * - The CA is handed over as a **provider**, not a value. [configure] runs inside
 *   `Instrumentation.onCreate`, where `InstrumentationRegistry` is not populated yet, so
 *   reading the asset eagerly here throws *"No instrumentation registered!"*. The lambda
 *   is called on the first intercepted connection, by which time everything is up.
 * - [isEnabled] is forced on. The default asks for `-e interpose true`, so that a build
 *   can carry this runner permanently and pay for it only when asked; this app exists to
 *   demonstrate capture, so it always captures.
 */
class ExampleInterposeRunner : InterposeRunner() {

    override fun isEnabled(arguments: Bundle): Boolean = true

    override fun configure(config: InterposeConfig, arguments: Bundle) {
        super.configure(config, arguments)

        config.certificateAuthority {
            CertificateAuthority.fromPem(
                InstrumentationRegistry.getInstrumentation().context.assets
                    .open("interpose-ca.pem").bufferedReader().use { it.readText() }
            )
        }

        // A host matches itself and its subdomains. Everything else the device talks to
        // during the run is still tunnelled — visible as a connection, not readable.
        config.intercept("example.com")
    }
}
