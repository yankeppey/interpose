package eu.buney.interpose.example

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.buney.interpose.Interpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a consumer's test actually looks like: launch the app, then read what it sent.
 *
 * Nothing here configures a client, installs a certificate or knows how the app makes
 * requests — which is the point. The `WebView` leg is traffic no interceptor in this
 * process could have seen, and the test asserts on it the same way as on the app's own
 * `OkHttpClient`.
 *
 * This one talks to the real `example.com`, unlike Interpose's own suite, which stands up
 * a `MockWebServer`. That is deliberate for an example — it shows a genuine HTTP/2
 * negotiation against a host nobody here controls — and it does mean the test needs
 * network.
 */
@RunWith(AndroidJUnit4::class)
class ExampleCaptureTest {

    @Test
    fun bothTheAppsOwnClientAndItsWebViewAreCaptured() {
        // If this fails, the runner never ran: check testInstrumentationRunner.
        assertTrue("Interpose is not installed", Interpose.isInstalled())
        val proxy = Interpose.proxy()

        ActivityScenario.launch(MainActivity::class.java).use {
            // awaitExchange returns null on timeout; say which leg went missing rather
            // than letting it surface as "expected 200 but was null".
            val fromOkHttp = requireNotNull(
                proxy.awaitExchange(CLIENT_TIMEOUT_MS) { it.params["from"] == "okhttp" }
            ) { "nothing captured from the app's OkHttpClient within ${CLIENT_TIMEOUT_MS}ms" }

            val fromWebView = requireNotNull(
                proxy.awaitExchange(WEBVIEW_TIMEOUT_MS) { it.params["from"] == "webview" }
            ) { "nothing captured from the WebView within ${WEBVIEW_TIMEOUT_MS}ms" }

            Log.i(TAG, "okhttp:  $fromOkHttp")
            Log.i(TAG, "webview: $fromWebView")

            // The client built during Application.onCreate, before any test code ran.
            assertEquals(200, fromOkHttp.status)
            assertEquals("example.com", fromOkHttp.host)
            // Both legs h2: the app was exercised on the transport it uses in production,
            // not on an HTTP/1.1 downgrade introduced by the act of watching it.
            assertEquals("h2", fromOkHttp.clientProtocol)
            assertEquals("h2", fromOkHttp.upstreamProtocol)

            // A stack we have no handle on, asserted on identically.
            assertEquals(200, fromWebView.status)
            assertEquals("example.com", fromWebView.host)
        }
    }

    private companion object {
        const val TAG = "InterposeExample"

        const val CLIENT_TIMEOUT_MS = 30_000L

        /**
         * Much longer, and measured rather than guessed: on a freshly booted emulator
         * Chromium's own processes took ~34s to come up before the WebView issued its
         * first request. The cost is paid once per device, and only when this fails.
         */
        const val WEBVIEW_TIMEOUT_MS = 90_000L
    }
}
