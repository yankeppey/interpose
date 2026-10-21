package eu.buney.interpose.example

import android.app.Application
import okhttp3.OkHttpClient

/**
 * An app that builds its HTTP client in `onCreate` — which is the ordinary thing to do,
 * and the reason [eu.buney.interpose.InterposeRunner] has to exist.
 *
 * OkHttp reads the default `ProxySelector` once, here, as the client is constructed. By
 * the time a test's `@Before` runs, this client has already decided it has no proxy and
 * will go on believing that for the life of the process. Installing from
 * `Instrumentation.onCreate` — before `callApplicationOnCreate` — is what gets in front
 * of this line.
 *
 * Built eagerly rather than lazily on purpose: a `by lazy` here would quietly make the
 * test pass for the wrong reason, because the client would not exist until something
 * asked for it, long after installation.
 */
class ExampleApplication : Application() {

    lateinit var http: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        http = OkHttpClient.Builder().build()
    }
}
