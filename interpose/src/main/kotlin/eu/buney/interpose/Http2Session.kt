package eu.buney.interpose

import android.util.Log
import okhttp3.Protocol
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Header
import okhttp3.internal.http2.Http2Connection
import okhttp3.internal.http2.Http2Stream
import okio.buffer
import java.io.IOException
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Serves intercepted HTTP/2 using OkHttp's own h2 codec, so an app that negotiated h2
 * keeps it through the proxy instead of being quietly downgraded.
 *
 * Nothing here implements HPACK, Huffman, framing or flow control. `Http2Connection` does
 * all of it, in server mode, from the artifact the test already depends on — and the
 * reference for driving it that way is `MockWebServer`, which has been serving h2 over TLS
 * on Android out of these same classes for years. This class is the ~100 lines that
 * `MockWebServer` spends answering from a queue and we spend forwarding to the origin.
 *
 * **It is a semantic proxy, not a frame relay.** A request is decoded to headers and a
 * body, replayed upstream by [UpstreamRelay], and the answer re-encoded onto the same
 * stream. Stream ids and frame boundaries are therefore the proxy's, not the origin's —
 * the same trade mitmproxy makes. What *is* preserved is the thing worth preserving: the
 * client multiplexes real concurrent streams over one connection and gets real h2
 * responses back, so h2-only defects (request loss under multiplexing is the plausible
 * one) can still show up.
 *
 * The caller owns nothing after [serve] returns. `Http2Connection` keeps reading on a
 * task-runner thread and closes the socket when the peer goes away, which is why the
 * accept loop must hand the socket over rather than close it.
 *
 * Tied to `okhttp3.internal.*`, which carries no compatibility promise — see
 * [InterposeProxy] on how that is pinned.
 */
internal class Http2Session(
    private val socket: Socket,
    private val defaultAuthority: String,
    private val relay: UpstreamRelay,
    /** What to offer the origin — see `UpstreamProtocol`, which decides it. */
    private val offering: Protocol,
    private val onExchange: (CapturedExchange) -> Unit,
) : Http2Connection.Listener() {

    fun serve(): Http2Connection {
        val connection = Http2Connection.Builder(false, TaskRunner.INSTANCE)
            .socket(socket)
            .listener(this)
            .build()
        // Server mode: this writes SETTINGS only — the client preface is the client's job.
        connection.start()
        return connection
    }

    /** Called on its own task queue per stream, so blocking on the origin here is fine. */
    override fun onStream(stream: Http2Stream) {
        val received = System.currentTimeMillis()
        val headers = stream.takeHeaders()
        val method = headers[":method"] ?: "GET"
        val path = headers[":path"] ?: "/"
        val scheme = headers[":scheme"] ?: "https"
        val authority = headers[":authority"] ?: headers["host"] ?: defaultAuthority
        val url = "$scheme://$authority$path"

        // A client that half-closed on HEADERS leaves this exhausted immediately; one
        // still uploading gets a bounded wait rather than parking a thread forever.
        stream.readTimeout().timeout(BODY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val body = runCatching { stream.getSource().buffer().readByteArray() }
            .getOrElse { ByteArray(0) }
            .takeIf { it.isNotEmpty() }

        val response = runCatching {
            relay.execute(ProxiedRequest(method, url, headers, body), offering)
        }.getOrElse { failure ->
            Log.w(TAG, "upstream failed for $url: ${failure.message}")
            writeFailure(stream)
            return
        }

        onExchange(
            CapturedExchange(
                method = method,
                url = url,
                host = authority.substringBefore(':'),
                path = path.substringBefore('?'),
                requestHeaders = headers,
                requestBody = body?.toString(Charsets.UTF_8),
                status = response.status,
                responseHeaders = response.headers,
                responseBody = response.body.toString(Charsets.UTF_8),
                clientProtocol = CLIENT_PROTOCOL,
                upstreamProtocol = response.protocol.toString(),
                atMs = received,
            )
        )

        writeResponse(stream, response)
    }

    private fun writeResponse(stream: Http2Stream, response: ProxiedResponse) {
        // `:status` first and header names lowercased: both are h2 requirements, and a
        // violation here surfaces as the client resetting the stream rather than as an
        // error on this side.
        val outHeaders = mutableListOf(Header(Header.RESPONSE_STATUS, response.status.toString()))
        response.headers.forEach { (name, value) -> outHeaders += Header(name.lowercase(), value) }
        // A 204 or 304 may not carry one at all, and the body is already known empty.
        if (response.status !in BODILESS_STATUSES) {
            outHeaders += Header("content-length", response.body.size.toString())
        }

        val outFinished = response.body.isEmpty()
        stream.writeHeaders(outHeaders, outFinished, flushHeaders = true)
        if (!outFinished) {
            // Closing the sink is what sets END_STREAM.
            stream.getSink().buffer().use { it.write(response.body) }
        }
    }

    private fun writeFailure(stream: Http2Stream) {
        runCatching {
            stream.writeHeaders(
                listOf(
                    Header(Header.RESPONSE_STATUS, "502"),
                    Header("content-length", "0"),
                ),
                outFinished = true,
                flushHeaders = true,
            )
        }.onFailure {
            runCatching { stream.close(ErrorCode.INTERNAL_ERROR, it as? IOException) }
        }
    }

    private companion object {
        const val TAG = "Interpose"
        const val BODY_TIMEOUT_SECONDS = 30L

        /** okhttp's own name for it, so a recorded value cannot drift from `Protocol`. */
        val CLIENT_PROTOCOL = Protocol.HTTP_2.toString()

        val BODILESS_STATUSES = setOf(204, 304)
    }
}
