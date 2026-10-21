package eu.buney.interpose

import android.util.Log
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.internal.http1.HeadersReader
import okio.Buffer
import okio.buffer
import okio.sink
import okio.source
import java.net.Socket

/**
 * Serves intercepted HTTP/1.1.
 *
 * Kept because ALPN can land here — an old WebView, a client that offers only 1.1, or a
 * proxy configured without h2 — not because it is the interesting path. [Http2Session] is.
 *
 * Everything that can be borrowed is borrowed. `HeadersReader` is okhttp's own, so the
 * request line and headers come with its 256 KiB limit; okio does the framing reads, so
 * there is no partial-read arithmetic here. What is left ours is the chunked loop, and
 * that is not for want of looking: okhttp's `ChunkedSource` and `FixedLengthSource` are
 * `private inner class`es of `Http1ExchangeCodec`, which is the *client* codec and decodes
 * responses rather than requests. `MockWebServer` hand-rolls the same loop for the same
 * reason.
 */
internal class Http1Session(
    socket: Socket,
    private val host: String,
    private val port: Int,
    private val relay: UpstreamRelay,
    /** What to offer the origin — see `UpstreamProtocol`, which decides it. */
    private val offering: Protocol,
    private val onExchange: (CapturedExchange) -> Unit,
) {

    // Buffered, unlike the CONNECT line InterposeProxy reads off the raw socket: from here
    // on this session owns the stream to the end, so reading ahead strands nothing.
    private val source = socket.source().buffer()
    private val sink = socket.sink().buffer()
    private val headers = HeadersReader(source)

    fun serve() {
        // Keep-alive: a burst of requests reuses one connection, so serve until it closes.
        while (serveOneRequest()) {
            // Loop body intentionally empty; serveOneRequest says whether to go on.
        }
    }

    /** Returns true when the connection may carry another request. */
    private fun serveOneRequest(): Boolean {
        if (source.exhausted()) return false // The peer went away.

        val requestLine = headers.readLine()
        val parts = requestLine.split(' ')
        if (parts.size < 3) return false
        val (method, path) = parts[0] to parts[1]

        val requestHeaders = headers.readHeaders()
        val body = readBody(requestHeaders)

        val authority = if (port == 443) host else "$host:$port"
        val url = "https://$authority$path"
        val received = System.currentTimeMillis()

        val response = runCatching {
            relay.execute(ProxiedRequest(method, url, requestHeaders, body), offering)
        }.getOrElse { failure ->
            Log.w(TAG, "upstream failed for $url: ${failure.message}")
            writeSimple(502, "Interpose could not reach upstream")
            return false
        }

        onExchange(
            CapturedExchange(
                method = method,
                url = url,
                host = host,
                path = path.substringBefore('?'),
                requestHeaders = requestHeaders,
                // Decoded for the record only. The bytes above are what goes upstream, so
                // a body that is not UTF-8 is forwarded intact and merely reads oddly here.
                requestBody = body?.toString(Charsets.UTF_8),
                status = response.status,
                responseHeaders = response.headers,
                responseBody = response.body.toString(Charsets.UTF_8),
                clientProtocol = CLIENT_PROTOCOL,
                upstreamProtocol = response.protocol.toString(),
                atMs = received,
            )
        )

        writeResponse(response)
        return !requestHeaders["connection"].equals("close", ignoreCase = true)
    }

    /**
     * `readByteArray` throws rather than returning a short body, which is the behaviour
     * worth having: a truncated body that looks complete is a false assertion.
     */
    private fun readBody(requestHeaders: Headers): ByteArray? {
        requestHeaders["content-length"]?.toLongOrNull()?.let { length ->
            return if (length <= 0L) null else source.readByteArray(length)
        }
        if (requestHeaders["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return readChunked()
        }
        return null
    }

    private fun readChunked(): ByteArray {
        val body = Buffer()
        while (true) {
            val size = source.readUtf8LineStrict().substringBefore(';').trim().toLongOrNull(16)
                ?: break
            if (size == 0L) {
                source.readUtf8LineStrict() // Trailing CRLF after the terminating chunk.
                break
            }
            source.readFully(body, size)
            source.readUtf8LineStrict() // CRLF after each chunk.
        }
        return body.readByteArray()
    }

    private fun writeResponse(response: ProxiedResponse) {
        sink.writeUtf8("HTTP/1.1 ${response.status} ${response.message}\r\n")
        response.headers.forEach { (name, value) -> sink.writeUtf8("$name: $value\r\n") }
        // Always our own framing: the upstream's Transfer-Encoding and Content-Length
        // described a body OkHttp has already decoded.
        sink.writeUtf8("Content-Length: ${response.body.size}\r\n\r\n")
        sink.write(response.body)
        sink.flush()
    }

    private fun writeSimple(status: Int, message: String) {
        sink.writeUtf8("HTTP/1.1 $status $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        sink.flush()
    }

    private companion object {
        const val TAG = "Interpose"

        /** okhttp's own name for it, so a recorded value cannot drift from `Protocol`. */
        val CLIENT_PROTOCOL = Protocol.HTTP_1_1.toString()
    }
}
