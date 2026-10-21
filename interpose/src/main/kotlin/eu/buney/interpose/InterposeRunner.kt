package eu.buney.interpose

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * An `AndroidJUnitRunner` that installs [Interpose] early enough to matter.
 *
 * "Early enough" is the whole reason this class exists. OkHttp resolves the default
 * `ProxySelector` once, when a client is constructed, and an app that builds its HTTP
 * client during `Application.onCreate` has therefore already decided before any `@Before`
 * runs. `Instrumentation.onCreate` happens before `callApplicationOnCreate`, so this is
 * the last point at which the decision is still open.
 *
 * Point the build at it:
 *
 * ```kotlin
 * android {
 *     defaultConfig {
 *         testInstrumentationRunner = "eu.buney.interpose.InterposeRunner"
 *     }
 * }
 * ```
 *
 * It does nothing unless asked, so an ordinary test run is unaffected:
 *
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.interpose=true
 *
 * adb shell am instrument -w \
 *   -e interpose true \
 *   -e interpose.intercept api.example.com+analytics.example.com \
 *   com.example.app.test/eu.buney.interpose.InterposeRunner
 * ```
 *
 * Interception needs a CA, which cannot come from a command-line argument — so for
 * anything beyond watching hosts, subclass and override [configure]:
 *
 * ```kotlin
 * class MyRunner : InterposeRunner() {
 *     override fun configure(config: InterposeConfig, arguments: Bundle) {
 *         super.configure(config, arguments)
 *         config.certificateAuthority(CertificateAuthority.fromPem(readAsset("ca.pem")))
 *         config.intercept("api.example.com")
 *     }
 * }
 * ```
 */
public open class InterposeRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle) {
        // Before super, which is what eventually reaches Application.onCreate.
        if (isEnabled(arguments)) {
            Interpose.install { configure(this, arguments) }
        }
        super.onCreate(arguments)
    }

    /**
     * Whether to install at all. Defaults to the `interpose` instrumentation argument, so
     * that a build can carry this runner permanently and only pay for it when asked.
     */
    protected open fun isEnabled(arguments: Bundle): Boolean =
        arguments.getString(ARG_ENABLED)?.toBooleanStrictOrNull() == true

    /**
     * Fills in the configuration. The default reads [ARG_INTERCEPT] and nothing else;
     * override to add a [CertificateAuthority] and whatever else the app needs, calling
     * `super` first to keep the argument handling.
     */
    protected open fun configure(config: InterposeConfig, arguments: Bundle) {
        arguments.getString(ARG_INTERCEPT)
            ?.split(HOST_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { config.intercept(it) }
    }

    public companion object {
        /** `-e interpose true` turns the proxy on. */
        public const val ARG_ENABLED: String = "interpose"

        /** `-e interpose.intercept a.com+b.com` — hosts to terminate TLS for. */
        public const val ARG_INTERCEPT: String = "interpose.intercept"

        /**
         * `+` rather than `,`: AGP mangles commas when forwarding instrumentation
         * arguments through Gradle, so a comma-separated list arrives truncated.
         */
        private const val HOST_SEPARATOR = '+'
    }
}
