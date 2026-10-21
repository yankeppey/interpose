package eu.buney.interpose

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

/**
 * Points `WebView` at the proxy, if `androidx.webkit` is on the classpath.
 *
 * WebView runs its own network stack and ignores `ProxySelector` entirely, so capturing
 * what a WebView loads needs this separate call. It is the only way in: there is no
 * supported hook into Chromium's networking from inside the app.
 *
 * `androidx.webkit` is `compileOnly` for Interpose, so an app with no WebView carries no
 * extra dependency and loses nothing. [isAvailable] decides before anything typed is
 * touched, which is why the real work sits in [Impl] — a class that is never loaded when
 * the dependency is missing, so its unresolvable references never have to verify.
 */
internal object WebViewProxy {

    private val isAvailable: Boolean by lazy {
        runCatching { Class.forName("androidx.webkit.ProxyController") }.isSuccess
    }

    /** Returns false if `androidx.webkit` is absent or the WebView is too old. */
    fun setOverride(hostPort: String): Boolean {
        if (!isAvailable) return false
        return Impl.setOverride(hostPort)
    }

    fun clearOverride() {
        if (!isAvailable) return
        Impl.clearOverride()
    }

    private object Impl {

        fun setOverride(hostPort: String): Boolean {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false
            val config = ProxyConfig.Builder()
                .addProxyRule(hostPort)
                // Fall through to direct if the proxy goes away mid-run, so a WebView does
                // not simply stop loading.
                .addDirect()
                .build()
            ProxyController.getInstance().setProxyOverride(config, { it.run() }, {})
            return true
        }

        fun clearOverride() {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
            ProxyController.getInstance().clearProxyOverride({ it.run() }, {})
        }
    }
}
