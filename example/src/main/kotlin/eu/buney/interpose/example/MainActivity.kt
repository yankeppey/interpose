package eu.buney.interpose.example

import android.app.Activity
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Two HTTP stacks, one screen — which is the situation Interpose is for.
 *
 * The first request goes through the app's own `OkHttpClient`, the kind of traffic an
 * interceptor would already show you. The second is a `WebView` load: a separate network
 * stack inside the same process that no interceptor of ours can reach, and that ignores
 * `ProxySelector` entirely.
 *
 * Both go to `example.com`, which is IANA's documentation domain and answers over HTTP/2,
 * so the example shows a real h2 negotiation rather than a staged one. They are told
 * apart by a `from` query parameter, which is also what the test asserts on.
 */
class MainActivity : Activity() {

    private val background = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply { text = "requesting…" }
        val webView = WebView(this).apply {
            // Chromium caches `example.com` for a week, so the second run of the test
            // would load it from disk, send nothing, and fail for a reason that has
            // nothing to do with capture. A proxy can only see requests that happen.
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(status)
                addView(webView)
            }
        )

        background.execute {
            val app = application as ExampleApplication
            val line = runCatching {
                app.http.newCall(Request.Builder().url(OKHTTP_URL).build()).execute().use {
                    "okhttp: ${it.code} over ${it.protocol}"
                }
            }.getOrElse { "okhttp: ${it.message}" }
            runOnUiThread { status.text = line }
        }

        webView.loadUrl(WEBVIEW_URL)
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        val OKHTTP_URL = "https://example.com/?from=okhttp"
        val WEBVIEW_URL = "https://example.com/?from=webview"
    }
}
