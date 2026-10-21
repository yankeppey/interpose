package eu.buney.interpose

import android.util.Log
import okhttp3.Protocol
import okhttp3.internal.http2.Http2Connection
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/**
 * An HTTP proxy running inside the app under test, so a test can see what the app asked
 * for without a device-wide proxy, an external process, or a `VpnService`.
 *
 * Build it with [interposeProxy]; [Interpose.install] also points the process at it.
 *
 * ```kotlin
 * val proxy = interposeProxy {
 *     certificateAuthority(ca)
 *     intercept("api.example.com")
 * }.apply { start() }
 * ```
 *
 * TLS is terminated **selectively**. A `CONNECT` to a host named by
 * [InterposeConfig.intercept] is answered, wrapped in a server-side `SSLSocket` holding a
 * certificate [CertificateAuthority] just signed for that host, and the requests inside
 * are parsed, recorded in full and replayed upstream with OkHttp. Everything else is
 * relayed byte for byte with its TLS untouched — visible as a [CapturedConnection], but
 * not readable.
 *
 * ## It keeps HTTP/2
 *
 * The proxy offers the configured protocols over ALPN and serves whichever the app picks —
 * h2 through [Http2Session], HTTP/1.1 through [Http1Session]. [UpstreamRelay] then
 * negotiates ALPN again on the way out, by default offering the origin exactly what the
 * app chose ([UpstreamProtocol.MIRROR_CLIENT]), so **the protocol changes in neither
 * direction** and the app is exercised on the transport it really ships with.
 *
 * That matters because the alternative hides bugs. An app downgraded to HTTP/1.1 for the
 * duration of a test cannot exhibit an HTTP/2-specific defect — request loss under
 * multiplexing being the obvious one — and those are exactly the defects that are hardest
 * to find any other way.
 *
 * None of the HTTP/2 implementation is here. OkHttp ships a complete one and already
 * drives it in server mode; [Http2Session] is the shell around it.
 *
 * ## What it cannot see
 *
 * - **HTTP/3 and QUIC.** They are UDP, and a TCP proxy is structurally blind to them.
 * - **Anything that ignores `ProxySelector`.** Most things honour it; see [Interpose].
 * - **A host the app pins certificates for.** Pinning defeats interception by design;
 *   relax it in the debug build or leave that host tunnelled.
 *
 * ## Dependency note
 *
 * The HTTP/2 path calls `okhttp3.internal.http2`, which carries no compatibility promise.
 * If a future OkHttp changes it, [h2CodecUsable] notices before any connection is
 * accepted and the proxy stops offering h2 rather than failing — intercepted traffic
 * degrades to HTTP/1.1 and keeps working. The error is logged loudly.
 */
public class InterposeProxy internal constructor(config: InterposeConfig) {

    // Snapshotted, so a config kept around and mutated later cannot change a running proxy.
    private val interceptHosts = config.interceptHosts.toSet()
    private val protocols = config.protocols
    private val upstreamProtocol = config.upstreamProtocol
    private val certificateAuthority = config.certificateAuthorityOrNull()
    private val relay = UpstreamRelay(config.buildUpstreamClient())

    private val recorded = Collections.synchronizedList(mutableListOf<CapturedConnection>())
    private val recordedExchanges = Collections.synchronizedList(mutableListOf<CapturedExchange>())
    private val waiters = Collections.synchronizedList(mutableListOf<Waiter>())

    /**
     * h2 connections outlive the accept-loop task that created them — the codec keeps
     * reading on its own thread — so [stop] needs a handle on them.
     */
    private val openH2 = Collections.newSetFromMap(ConcurrentHashMap<Http2Connection, Boolean>())

    private var server: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "interpose").apply { isDaemon = true }
    }

    /**
     * Whether OkHttp's internal HTTP/2 codec is still the shape this proxy drives.
     *
     * Probed reflectively, once, up front — because ALPN cannot be walked back. Once a
     * client has negotiated h2 there is no honest way to serve it HTTP/1.1, so the check
     * has to happen before the protocol list is offered rather than when the codec is
     * first called. A `LinkageError` thrown mid-handshake would also escape the catch
     * around the worker and hang the app's request.
     */
    private val h2CodecUsable: Boolean by lazy {
        runCatching {
            Http2Connection.Builder::class.java.getMethod("socket", Socket::class.java)
        }.isSuccess.also { usable ->
            if (!usable) {
                Log.e(
                    TAG,
                    "OkHttp's internal HTTP/2 codec is not the shape Interpose expects, so " +
                        "intercepted connections will be served over HTTP/1.1. This usually " +
                        "means a newer OkHttp than this version of Interpose supports.",
                )
            }
        }
    }

    private val offeredProtocols: List<Protocol>
        get() = if (h2CodecUsable) protocols else protocols - Protocol.HTTP_2

    /** The loopback port to point clients at. Throws if the proxy is not [start]ed. */
    public val port: Int get() = server?.localPort ?: error("Proxy not started")

    public fun start() {
        // Loopback only: nothing outside this process can reach it, so a proxy left
        // running is not an open relay on whatever network the device is on.
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
        server = socket
        workers.execute { acceptLoop(socket) }
        Log.i(
            TAG,
            "listening on $LOOPBACK:${socket.localPort}, intercepting $interceptHosts, " +
                "offering ${offeredProtocols.joinToString()}",
        )
    }

    public fun stop() {
        try {
            server?.close()
        } catch (_: IOException) {
            // Closing the listener is what ends acceptLoop; a failure here is immaterial.
        }
        server = null
        openH2.forEach { runCatching { it.close() } }
        openH2.clear()
        workers.shutdownNow()
    }

    /** Every connection seen so far, intercepted or tunnelled, oldest first. */
    public fun connections(): List<CapturedConnection> =
        synchronized(recorded) { recorded.toList() }

    /** Every request seen so far on an intercepted connection, oldest first. */
    public fun exchanges(): List<CapturedExchange> =
        synchronized(recordedExchanges) { recordedExchanges.toList() }

    /**
     * Waits for an intercepted request matching [predicate], returning it or null on
     * timeout. Checks what has already been recorded first, since a request made during
     * `Application.onCreate` arrives long before any test body runs.
     */
    public fun awaitExchange(
        timeoutMs: Long,
        predicate: (CapturedExchange) -> Boolean,
    ): CapturedExchange? {
        synchronized(recordedExchanges) {
            recordedExchanges.firstOrNull(predicate)?.let { return it }
        }
        val waiter = Waiter(predicate)
        waiters.add(waiter)
        return try {
            if (waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) waiter.hit else null
        } finally {
            waiters.remove(waiter)
        }
    }

    /** As [awaitExchange], but at connection level, so it also sees tunnelled hosts. */
    public fun awaitConnection(
        timeoutMs: Long,
        predicate: (CapturedConnection) -> Boolean,
    ): CapturedConnection? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(recorded) { recorded.firstOrNull(predicate) }?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return // stop() closed the listener.
            }
            workers.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        // An h2 connection takes the socket with it and closes it when the peer leaves,
        // so this has to be able to decline to close it.
        var handedOff = false
        try {
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val headerBytes = drainHeaders(input)

            val parts = requestLine.split(' ')
            if (parts.size < 3) return

            handedOff = if (parts[0].equals("CONNECT", ignoreCase = true)) {
                connect(client, input, parts[1], requestLine)
            } else {
                forwardCleartext(client, input, parts[1], requestLine, headerBytes)
                false
            }
        } catch (e: Exception) {
            // A proxy that throws must not take the app's request down with it; the app
            // sees a closed connection and retries on its own terms.
            Log.w(TAG, "connection failed: ${e.message}")
        } finally {
            if (!handedOff) runCatching { client.close() }
        }
    }

    /** Returns true if ownership of [client] moved to something that outlives this call. */
    private fun connect(
        client: Socket,
        input: InputStream,
        target: String,
        requestLine: String,
    ): Boolean {
        val host = target.substringBeforeLast(':')
        val port = target.substringAfterLast(':').toIntOrNull() ?: HTTPS_PORT
        val intercept = shouldIntercept(host)
        val connection = record(CapturedConnection(host, port, requestLine, intercept, now()))

        client.getOutputStream().apply {
            write(ESTABLISHED.toByteArray())
            flush()
        }

        if (intercept) return interceptTls(client, host, port, connection)

        Socket(host, port).use { upstreamSocket ->
            relay(
                input,
                upstreamSocket.getOutputStream(),
                upstreamSocket.getInputStream(),
                client.getOutputStream(),
            )
        }
        return false
    }

    private fun shouldIntercept(host: String) = interceptHosts.any { suffix ->
        host == suffix || host.endsWith(".$suffix")
    }

    /**
     * Terminates TLS with a certificate for [host] and serves the requests inside over
     * whatever ALPN chose.
     *
     * ALPN is [ServerAlpn]'s job: `SSLParameters.setApplicationProtocols` on API 29+,
     * reflection into the platform's own TLS stack below it. A client that offers nothing,
     * or a platform that cannot negotiate, reports null and falls through to HTTP/1.1.
     */
    private fun interceptTls(
        client: Socket,
        host: String,
        port: Int,
        connection: CapturedConnection,
    ): Boolean {
        val ca = certificateAuthority?.value ?: return false
        val ssl = ca.forHost(host).sslContext().socketFactory
            .createSocket(client, host, port, false) as SSLSocket
        ssl.useClientMode = false

        ServerAlpn.configure(ssl, offeredProtocols)
        ssl.startHandshake()
        val negotiated = ServerAlpn.selected(ssl)
        connection.clientProtocol = negotiated ?: Protocol.HTTP_1_1.toString()
        Log.i(TAG, "[intercept] $host:$port alpn=${connection.clientProtocol}")

        val authority = if (port == HTTPS_PORT) host else "$host:$port"
        if (negotiated == Protocol.HTTP_2.toString()) {
            // Mirroring an h2 client means offering h2 upstream and letting OkHttp fall
            // back if the origin cannot — the best a proxy can do, so both policies agree.
            val h2 = Http2Session(ssl, authority, relay, Protocol.HTTP_2, ::record).serve()
            openH2.add(h2)
            return true // The codec owns the socket now.
        }

        val offering = when (upstreamProtocol) {
            UpstreamProtocol.MIRROR_CLIENT -> Protocol.HTTP_1_1
            UpstreamProtocol.PREFER_HTTP_2 -> Protocol.HTTP_2
        }
        ssl.use { Http1Session(ssl, host, port, relay, offering, ::record).serve() }
        return false
    }

    private fun forwardCleartext(
        client: Socket,
        input: InputStream,
        target: String,
        requestLine: String,
        headerBytes: ByteArray,
    ) {
        // Absolute form is what a proxy receives: GET http://host:port/path HTTP/1.1
        val authority = target.removePrefix("http://").substringBefore('/')
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "$HTTP_PORT").toIntOrNull() ?: HTTP_PORT
        record(CapturedConnection(host, port, requestLine, intercepted = false, atMs = now()))

        Socket(host, port).use { upstreamSocket ->
            upstreamSocket.getOutputStream().apply {
                write("$requestLine\r\n".toByteArray())
                write(headerBytes)
                flush()
            }
            relay(
                input,
                upstreamSocket.getOutputStream(),
                upstreamSocket.getInputStream(),
                client.getOutputStream(),
            )
        }
    }

    /** Pumps both directions until either side closes. */
    private fun relay(
        clientIn: InputStream,
        upstreamOut: OutputStream,
        upstreamIn: InputStream,
        clientOut: OutputStream,
    ) {
        val up = workers.submit { copy(clientIn, upstreamOut) }
        copy(upstreamIn, clientOut)
        up.get(RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun copy(from: InputStream, to: OutputStream) {
        try {
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = from.read(buffer)
                if (read == -1) break
                to.write(buffer, 0, read)
                to.flush()
            }
        } catch (_: Exception) {
            // Either end closing mid-relay is the normal way a connection ends.
        }
    }

    private fun record(connection: CapturedConnection): CapturedConnection {
        synchronized(recorded) { recorded.add(connection) }
        if (!connection.intercepted) Log.i(TAG, "[tunnel] ${connection.host}:${connection.port}")
        return connection
    }

    private fun record(exchange: CapturedExchange) {
        synchronized(recordedExchanges) { recordedExchanges.add(exchange) }
        Log.i(TAG, "$exchange")
        synchronized(waiters) { waiters.toList() }
            .filter { it.hit == null && it.predicate(exchange) }
            .forEach { it.hit = exchange; it.latch.countDown() }
    }

    /**
     * Reads one CRLF-terminated line a byte at a time, deliberately — this is the one
     * place that must not read ahead.
     *
     * [Http1Session] gets to use okio because it owns its stream to the end. Here the
     * socket is about to be handed to an `SSLSocket` (or to a byte relay), which reads
     * from the socket and not from any buffer of ours, so a buffered read that swallowed
     * part of the client's TLS `ClientHello` would strand it unrecoverably.
     */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return line.takeIf { it.isNotEmpty() }?.toString()
            if (c == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(c.toChar())
            if (line.length > MAX_LINE_BYTES) return line.toString()
        }
    }

    /** Everything up to and including the blank line, kept verbatim for forwarding. */
    private fun drainHeaders(input: InputStream): ByteArray {
        val out = StringBuilder()
        while (true) {
            val line = readLine(input) ?: break
            out.append(line).append("\r\n")
            if (line.isEmpty()) break
        }
        return out.toString().toByteArray()
    }

    private fun now() = System.currentTimeMillis()

    private class Waiter(val predicate: (CapturedExchange) -> Boolean) {
        val latch = CountDownLatch(1)

        @Volatile
        var hit: CapturedExchange? = null
    }

    // `val`, not `const val`, deliberately: a `const` in a companion is lifted to a
    // public static field on the enclosing class whatever the companion's own visibility
    // is, so `private` here buys nothing at the JVM level and these ten would sit in the
    // published ABI — binding us to every buffer size and timeout we picked. The ABI dump
    // is what noticed.
    private companion object {
        val TAG = "Interpose"
        val LOOPBACK = "127.0.0.1"
        val BACKLOG = 50
        val BUFFER_BYTES = 16 * 1024
        val MAX_LINE_BYTES = 8 * 1024
        val RELAY_TIMEOUT_MS = 60_000L
        val POLL_MS = 100L
        val HTTPS_PORT = 443
        val HTTP_PORT = 80
        val ESTABLISHED = "HTTP/1.1 200 Connection Established\r\n\r\n"
    }
}
