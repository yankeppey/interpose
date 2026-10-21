package eu.buney.interpose

import okhttp3.Headers
import java.net.URLDecoder

/**
 * One HTTP request and its response, as the proxy saw them with TLS terminated — so the
 * whole of both is readable.
 *
 * [clientProtocol] and [upstreamProtocol] are the fidelity record: `h2` on both means the
 * app was exercised on the same transport it uses in production. Worth asserting on in
 * any test that cares, because a downgrade is invisible in the request contents.
 *
 * The proxy hands you these; you never build one, which is why the constructor is
 * `internal` and this is not a `data class`. A data class would publish a twelve-argument
 * constructor, `component1`–`component12` and `copy` — so a field added here later, a
 * duration or the h2 stream id, would rewrite all three and surface as a breaking ABI
 * change to consumers who only ever call the getters. Nothing is given up: value equality
 * is meaningless for a record of an event, and [atMs] made two captures of the same
 * request unequal anyway.
 */
public class CapturedExchange internal constructor(
    public val method: String,
    public val url: String,
    public val host: String,
    /** Path only, with the query string stripped — assert on [params] for that. */
    public val path: String,
    public val requestHeaders: Headers,
    public val requestBody: String?,
    public val status: Int,
    public val responseHeaders: Headers,
    public val responseBody: String?,
    /** What the app spoke to the proxy: `h2` or `http/1.1`. */
    public val clientProtocol: String,
    /** What the proxy spoke to the origin, as OkHttp reported it. */
    public val upstreamProtocol: String,
    public val atMs: Long,
) {

    /** Query parameters only. */
    public val query: Map<String, String> get() = decode(url.substringAfter('?', ""))

    /**
     * Query parameters merged with a form-encoded body, the body winning on a clash.
     *
     * Prefer this over picking apart [url]: a client that moves a long query string into
     * a POST body sends the same information either way, and an assertion written against
     * [params] does not have to care which shape it took.
     */
    public val params: Map<String, String>
        get() {
            val body = requestBody?.takeIf { looksFormEncoded(it) }?.let { decode(it) } ?: emptyMap()
            return query + body
        }

    override fun toString(): String = "$method $url -> $status ($clientProtocol → $upstreamProtocol)"

    private fun looksFormEncoded(body: String) =
        !body.trimStart().startsWith("{") && body.contains('=')

    private fun decode(raw: String): Map<String, String> =
        raw.removePrefix("&").split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                urlDecode(pair.substringBefore('=')) to urlDecode(pair.substringAfter('=', ""))
            }

    private fun urlDecode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

/**
 * One connection the app opened, as the proxy saw it.
 *
 * [host] and [port] come from the `CONNECT` line, so they are the app's own intent rather
 * than a DNS result. [intercepted] says whether TLS was terminated — if it was, the
 * requests carried on this connection are in [InterposeProxy.exchanges] too.
 *
 * Useful on its own for the hosts you chose *not* to intercept: those stay encrypted end
 * to end, so this is all you learn about them, and often all you need.
 *
 * Not a `data class`, for the reasons given on [CapturedExchange] — and here one more:
 * [clientProtocol] is filled in after the record is published, so a generated `copy` would
 * have silently dropped the one field a fidelity assertion is most likely to read.
 */
public class CapturedConnection internal constructor(
    public val host: String,
    public val port: Int,
    public val requestLine: String,
    public val intercepted: Boolean,
    public val atMs: Long,
) {
    /**
     * What ALPN settled on with the app: `h2`, `http/1.1`, or null while the handshake is
     * still in flight, on a tunnelled connection, or if the handshake failed.
     *
     * Written after the record is published, because the record has to exist even for a
     * connection whose handshake never completes.
     */
    @Volatile
    public var clientProtocol: String? = null

    /** Terse on purpose: this is what an assertion failure prints. */
    override fun toString(): String =
        "$host:$port ${if (intercepted) "intercepted" else "tunnelled"}" +
            (clientProtocol?.let { " ($it)" } ?: "")
}
