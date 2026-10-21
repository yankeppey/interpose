package eu.buney.interpose

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy

/**
 * One request as the proxy received it, with the HTTP version it arrived over already
 * forgotten.
 *
 * This is the seam that lets HTTP/1.1 and HTTP/2 front-ends share everything downstream:
 * [Http1Session] parses bytes into this, [Http2Session] reads it out of HPACK-decoded
 * headers, and [UpstreamRelay] is the only thing that talks to the origin.
 */
internal class ProxiedRequest(
    val method: String,
    val url: String,
    val headers: Headers,
    val body: ByteArray?,
)

/** What the origin answered, with the body already decoded and buffered. */
internal class ProxiedResponse(
    val status: Int,
    val message: String,
    val headers: Headers,
    val body: ByteArray,
    val protocol: Protocol,
)

/**
 * Replays an intercepted request at the origin with OkHttp, and reports which protocol it
 * used to do so.
 *
 * OkHttp is doing two jobs here that are easy to miss. It negotiates ALPN on the upstream
 * leg, so an origin that speaks h2 is reached over h2 — without that the proxy would turn
 * every intercepted request into HTTP/1.1 no matter what the client asked for. And it owns
 * connection pooling, so a burst of requests on one multiplexed client connection becomes a
 * burst on one multiplexed upstream connection rather than a socket each.
 */
internal class UpstreamRelay(private val client: OkHttpClient = defaultClient()) {

    /**
     * For mirroring a client that only speaks HTTP/1.1.
     *
     * `newBuilder` shares the connection pool and dispatcher, which is what we want, and
     * sharing is safe because `protocols` is part of `Address` equality — so a pooled h2
     * connection can never be handed to a call that asked for 1.1.
     */
    private val http1Only: OkHttpClient by lazy {
        client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    }

    /**
     * [offering] is what to put in the upstream ALPN list, not a guarantee: asking for
     * HTTP/2 at an origin that does not support it still falls back to 1.1, and
     * [ProxiedResponse.protocol] reports what actually happened.
     */
    fun execute(request: ProxiedRequest, offering: Protocol): ProxiedResponse {
        val negotiator = if (offering == Protocol.HTTP_1_1) http1Only else client
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) ->
            if (!isHopByHop(name) && !name.startsWith(':')) builder.addHeader(name, value)
        }
        val body = request.body?.toRequestBody()
        builder.method(request.method, if (methodNeedsBody(request.method)) body ?: EMPTY_BODY else body)

        negotiator.newCall(builder.build()).execute().use { response ->
            val headers = Headers.Builder().apply {
                response.headers.forEach { (name, value) ->
                    if (name.lowercase() !in RESPONSE_HEADERS_TO_DROP) add(name.lowercase(), value)
                }
            }.build()
            return ProxiedResponse(
                status = response.code,
                message = response.message.ifEmpty { statusMessage(response.code) },
                headers = headers,
                body = response.body?.bytes() ?: ByteArray(0),
                protocol = response.protocol,
            )
        }
    }

    private fun isHopByHop(name: String) = name.lowercase() in REQUEST_HEADERS_TO_DROP

    private fun methodNeedsBody(method: String) =
        method.equals("POST", true) || method.equals("PUT", true) || method.equals("PATCH", true)

    /** HTTP/2 has no reason phrase, so a downgraded leg needs something to write. */
    private fun statusMessage(code: Int) = when (code) {
        200 -> "OK"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Status $code"
    }

    companion object {
        /**
         * The upstream client, which must not consult the default `ProxySelector` — that
         * is the Interpose proxy, and it would hand its own requests back to itself.
         *
         * `protocols` is the default, stated anyway because it is the whole point: drop
         * `HTTP_2` here and an intercepted h2 request silently reaches the origin as 1.1.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()

        private val EMPTY_BODY = ByteArray(0).toRequestBody()

        /**
         * Set by the connection rather than the request, or recomputed by OkHttp.
         * `accept-encoding` in particular: dropping it lets OkHttp add its own and
         * transparently decompress, so the body we record and forward is readable text.
         */
        private val REQUEST_HEADERS_TO_DROP = setOf(
            "host", "connection", "keep-alive", "proxy-connection", "te",
            "transfer-encoding", "content-length", "accept-encoding", "upgrade",
        )

        /**
         * Describe a body OkHttp already decoded, or a connection we do not reuse.
         * Everything here is also forbidden in HTTP/2, where a `connection` header is a
         * protocol error rather than a nuisance.
         */
        private val RESPONSE_HEADERS_TO_DROP = setOf(
            "content-length", "transfer-encoding", "content-encoding", "connection",
            "keep-alive", "proxy-connection", "upgrade", "trailer",
        )
    }
}
