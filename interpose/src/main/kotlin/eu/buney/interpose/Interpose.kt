package eu.buney.interpose

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Starts an [InterposeProxy] and points the whole process at it.
 *
 * ```kotlin
 * Interpose.install {
 *     certificateAuthority(CertificateAuthority.fromPem(pem))
 *     intercept("api.example.com")
 * }
 * // …app runs…
 * val request = Interpose.proxy().awaitExchange(10_000) { it.path == "/v1/session" }
 * ```
 *
 * ## Two calls, because Android has two proxy worlds
 *
 * - [ProxySelector.setDefault] covers everything built on `java.net`. OkHttp consults the
 *   default selector when no proxy is set on the client, `HttpURLConnection` uses it, and
 *   so do the many libraries layered on those two — HTTP clients, image loaders, media
 *   players.
 * - `WebView` has its own network stack and ignores the above entirely. It needs
 *   `androidx.webkit`'s `ProxyController`, which [installWebViewProxy] uses when that
 *   dependency is present.
 *
 * Between them this reaches most of what an app does, which is the point: an interceptor
 * on your own HTTP client only ever shows you the traffic you already control, and says
 * nothing about the WebView or about a third-party SDK you cannot see inside.
 *
 * ## Install it before `Application.onCreate`
 *
 * This is the single most important constraint and the least obvious one. OkHttp resolves
 * the default `ProxySelector` once, when the client is constructed, so a client built
 * during `Application.onCreate` has already decided before any test body runs.
 *
 * `Instrumentation.onCreate` runs before `callApplicationOnCreate`, which is early
 * enough. [InterposeRunner] is that hook; use it, or subclass `AndroidJUnitRunner`
 * yourself and call [install] before `super.onCreate`.
 */
public object Interpose {

    @Volatile
    private var proxy: InterposeProxy? = null
    private var previousSelector: ProxySelector? = null

    /** Whether [install] has run and not been undone. */
    public fun isInstalled(): Boolean = proxy != null

    /** The running proxy, or null if [install] has not been called. */
    public fun proxyOrNull(): InterposeProxy? = proxy

    /** The running proxy, or a clear failure if it was never installed. */
    public fun proxy(): InterposeProxy = checkNotNull(proxy) {
        "Interpose is not installed. Call Interpose.install { … } before " +
            "Application.onCreate — see InterposeRunner."
    }

    /**
     * Starts a proxy and routes the process through it. A second call while one is already
     * installed is ignored, so a runner and a test can both ask without coordinating.
     *
     * Nothing is intercepted unless [InterposeConfig.intercept] names it; without that you
     * get hosts and ports but not contents.
     */
    public fun install(configure: InterposeConfig.() -> Unit = {}) {
        if (proxy != null) return
        val started = interposeProxy(configure).apply { start() }
        proxy = started

        previousSelector = ProxySelector.getDefault()
        ProxySelector.setDefault(LoopbackSparingSelector(started.port, previousSelector))

        installWebViewProxy(started.port)
        Log.i(TAG, "installed on port ${started.port}")
    }

    /** Restores the previous routing and stops the proxy. */
    public fun uninstall() {
        ProxySelector.setDefault(previousSelector)
        previousSelector = null
        clearWebViewProxy()
        proxy?.stop()
        proxy = null
    }

    /**
     * The WebView proxy is process-wide and applied asynchronously, on a best effort: a
     * WebView too old to carry the feature leaves WebView traffic going direct, which
     * costs this capture the WebView's requests and nothing else.
     *
     * Reached reflectively so that `androidx.webkit` is an optional dependency — an app
     * with no WebView should not have to take one on. Posted to the main thread rather
     * than run inline, because WebView has to be touched there and [install] is called
     * from `Instrumentation.onCreate`, which is inside `handleBindApplication` before the
     * main looper is running the app.
     */
    private fun installWebViewProxy(port: Int) {
        Handler(Looper.getMainLooper()).post {
            runCatching { WebViewProxy.setOverride("127.0.0.1:$port") }
                .onSuccess { applied ->
                    if (applied) {
                        Log.i(TAG, "WebView proxy override applied")
                    } else {
                        Log.i(
                            TAG,
                            "androidx.webkit absent, or this WebView has no PROXY_OVERRIDE — " +
                                "WebView traffic stays direct",
                        )
                    }
                }
                .onFailure { Log.w(TAG, "WebView proxy override failed: ${it.message}") }
        }
    }

    private fun clearWebViewProxy() {
        runCatching { WebViewProxy.clearOverride() }
    }

    /**
     * Sends everything to the proxy except loopback, which would otherwise include the
     * proxy's own upstream connections and deadlock it against itself.
     *
     * `10.0.2.2` is spared too: that is the host machine as seen from an Android emulator,
     * so a test talking to a server on the development machine keeps working.
     */
    private class LoopbackSparingSelector(
        port: Int,
        private val fallback: ProxySelector?,
    ) : ProxySelector() {

        private val proxyList = listOf(
            Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", port))
        )

        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host ?: return proxyList
            return if (isLoopback(host)) listOf(Proxy.NO_PROXY) else proxyList
        }

        override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) {
            Log.w(TAG, "connect failed via proxy for ${uri?.host}: ${failure?.message}")
            fallback?.connectFailed(uri, address, failure)
        }

        private fun isLoopback(host: String) =
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2"
    }

    private const val TAG = "Interpose"
}
