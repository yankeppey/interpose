package eu.buney.interpose

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.BufferedSink
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

/**
 * The regression that is this library's reason to exist: an intercepted connection
 * **stays HTTP/2**.
 *
 * Every other embeddable JVM or Android MITM proxy stops at HTTP/1.1, which means
 * terminating TLS in-process has until now meant testing an app on a transport it never
 * ships with. These tests fail loudly if that comes back — whether because OkHttp's
 * internal HTTP/2 codec moved under an upgrade, or because someone narrowed the ALPN
 * offer.
 *
 * Self-contained on purpose: a `MockWebServer` is the origin, so there is no live host,
 * no network, and no dependence on any app's trust configuration. Three legs, all real:
 *
 * ```
 * OkHttp client  --CONNECT--> InterposeProxy --h2--> MockWebServer
 *                --- h2 --->                (ALPN negotiated on both sides)
 * ```
 *
 * The HTTP/1.1 tests at the end are the same claim from the other side: whatever the
 * protocol, what the proxy forwards must be what the client sent.
 */
@RunWith(AndroidJUnit4::class)
class ProxyFidelityTest {

    private lateinit var origin: MockWebServer
    private lateinit var proxy: InterposeProxy
    private lateinit var client: OkHttpClient

    /** Self-signed and local: the origin only ever answers to `127.0.0.1`. */
    private val originCertificate = HeldCertificate.Builder()
        .addSubjectAlternativeName(LOOPBACK)
        .build()

    /** Fresh per test — this suite drives its own client, so nothing has to pre-trust it. */
    private val ca = CertificateAuthority.generate()

    @Before
    fun setUp() {
        origin = MockWebServer().apply {
            protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            useHttps(
                HandshakeCertificates.Builder().heldCertificate(originCertificate).build()
                    .sslSocketFactory(),
                false,
            )
            start(InetAddress.getByName(LOOPBACK), 0)
        }
    }

    @After
    fun tearDown() {
        // Guarded on both: a tearDown that throws when setUp failed replaces the real
        // error with its own, which is the difference between a diagnosis and a puzzle.
        if (::proxy.isInitialized) proxy.stop()
        if (::origin.isInitialized) origin.shutdown()
    }

    @Test
    fun anInterceptedRequestIsHttp2OnBothLegs() {
        startProxy()
        origin.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val response = client.newCall(
            Request.Builder()
                .url(originUrl("/v1/event?name=start&id=42"))
                .post("session=abc123&kind=launch".toRequestBody())
                .build()
        ).execute()

        val bodyText = response.use { it.body!!.string() }
        Log.i(TAG, "client leg negotiated ${response.protocol}, body $bodyText")

        // The client's own view. Without this the next two could both say "h2" while the
        // app had already been downgraded before it ever reached the proxy.
        assertEquals(Protocol.HTTP_2, response.protocol)
        assertEquals("""{"ok":true}""", bodyText)

        val captured = awaitExchange { it.path == "/v1/event" }
        Log.i(TAG, "captured $captured")
        assertEquals("h2", captured.clientProtocol)
        assertEquals("h2", captured.upstreamProtocol)

        // ...and it is readable, which is the point of terminating TLS at all.
        assertEquals("POST", captured.method)
        assertEquals(200, captured.status)
        assertEquals("start", captured.params["name"])
        assertEquals("42", captured.params["id"])
        // params merges the query with a form-encoded body, so both shapes read alike.
        assertEquals("abc123", captured.params["session"])
        assertEquals("launch", captured.params["kind"])

        val atOrigin = origin.takeRequest(TIMEOUT_SECONDS, SECONDS)
        assertNotNull("The origin saw no request", atOrigin)
        assertEquals("session=abc123&kind=launch", atOrigin!!.body.readUtf8())
    }

    /**
     * Multiplexing survives, which is the part a semantic proxy could plausibly lose: six
     * concurrent requests have to share one TCP connection, not open six.
     *
     * This is also the shape of the defect the whole exercise is about — a burst of
     * requests on one multiplexed connection is exactly where h2-specific loss would
     * hide, and it cannot be reproduced at all on an HTTP/1.1-only proxy.
     */
    @Test
    fun concurrentStreamsShareOneInterceptedConnection() {
        startProxy()
        repeat(BURST + 1) { origin.enqueue(MockResponse().setBody("ok-$it")) }

        // One warm-up first: it is what puts an h2 connection in the pool for the burst to
        // reuse. Fire all seven cold and OkHttp races to open several.
        client.newCall(Request.Builder().url(originUrl("/warmup")).build()).execute().close()

        val pool = Executors.newFixedThreadPool(BURST)
        val results = try {
            (1..BURST).map { n ->
                // Callable, not Runnable: the protocol each leg negotiated is the result.
                pool.submit(
                    Callable {
                        client.newCall(Request.Builder().url(originUrl("/v1/event?n=$n")).build())
                            .execute().use { it.protocol }
                    }
                )
            }.map { it.get(TIMEOUT_SECONDS, SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertTrue("Some leg was not h2: $results", results.all { it == Protocol.HTTP_2 })

        val logged = (1..BURST).map { n -> awaitExchange { it.params["n"] == n.toString() } }
        assertTrue(
            "An intercepted stream was not h2: ${logged.map { it.clientProtocol }}",
            logged.all { it.clientProtocol == "h2" && it.upstreamProtocol == "h2" },
        )

        val intercepted = proxy.connections().filter { it.intercepted }
        Log.i(TAG, "${logged.size + 1} request(s) over ${intercepted.size} connection(s)")
        assertEquals(
            "Seven requests should have ridden one multiplexed connection, not " +
                "${intercepted.size}: $intercepted",
            1,
            intercepted.size,
        )
    }

    /**
     * A client that can only do HTTP/1.1 is carried on HTTP/1.1 **on both legs**, even
     * though this origin offers h2 — the statement of transparency.
     *
     * Narrowing the ALPN offer is also the only thing between here and the behaviour of
     * every other embeddable proxy, so this doubles as coverage of the HTTP/1.1
     * front-end — the fallback an old WebView lands on.
     */
    @Test
    fun anHttp1ClientIsCarriedOnHttp1OnBothLegs() {
        startProxy(protocols = listOf(Protocol.HTTP_1_1))
        origin.enqueue(MockResponse().setBody("one-one"))

        val response = client.newCall(Request.Builder().url(originUrl("/v1/event?v=1")).build())
            .execute()
        val bodyText = response.use { it.body!!.string() }

        assertEquals(Protocol.HTTP_1_1, response.protocol)
        assertEquals("one-one", bodyText)

        val captured = awaitExchange { it.path == "/v1/event" }
        assertEquals("http/1.1", captured.clientProtocol)
        // The origin would have taken h2 — see the next test — so this is the proxy
        // declining to upgrade, not the origin's limit.
        assertEquals("http/1.1", captured.upstreamProtocol)
    }

    /**
     * `PREFER_HTTP_2` opts out of mirroring, which is how you ask "what would this origin
     * do over h2?" about a host whose client cannot offer it — an app on
     * `HttpURLConnection`, for instance, which has no h2 to negotiate with.
     */
    @Test
    fun preferHttp2UpgradesTheOriginLegOnly() {
        startProxy(
            protocols = listOf(Protocol.HTTP_1_1),
            upstreamProtocol = UpstreamProtocol.PREFER_HTTP_2,
        )
        origin.enqueue(MockResponse().setBody("upgraded"))

        val response = client.newCall(Request.Builder().url(originUrl("/v1/event?v=2")).build())
            .execute()
        assertEquals(Protocol.HTTP_1_1, response.use { it.protocol })

        val captured = awaitExchange { it.path == "/v1/event" }
        assertEquals("http/1.1", captured.clientProtocol)
        assertEquals("h2", captured.upstreamProtocol)
    }

    /**
     * A request body is forwarded **byte for byte**, even when it is not text.
     *
     * Reading a body as a `String` and re-encoding it for the relay turns every byte
     * outside UTF-8 into U+FFFD, which makes the proxy change what the app sent. The
     * recorded `requestBody` stays a lossy view on purpose; the forwarded bytes are not.
     */
    @Test
    fun aBinaryRequestBodySurvivesTheHttp1Path() {
        startProxy(protocols = listOf(Protocol.HTTP_1_1))
        origin.enqueue(MockResponse().setBody("stored"))

        // Deliberately not valid UTF-8: a lone 0xFF, a lone 0x80 continuation byte.
        val binary = byteArrayOf(0x00, 0x01, 0x7F, -1, -2, -128, 0x42)
        client.newCall(
            Request.Builder()
                .url(originUrl("/upload"))
                .post(binary.toRequestBody("application/octet-stream".toMediaType()))
                .build()
        ).execute().close()

        val atOrigin = origin.takeRequest(TIMEOUT_SECONDS, SECONDS)
        assertNotNull("The origin saw no request", atOrigin)
        assertEquals(
            "The proxy altered the body on the way through",
            ByteString.of(*binary),
            atOrigin!!.body.readByteString(),
        )

        assertEquals("http/1.1", awaitExchange { it.path == "/upload" }.clientProtocol)
    }

    /**
     * A chunked request body — the one framing OkHttp lends no reader for, since its
     * chunked source is private to the client codec, so that loop is ours and needs its
     * own test. OkHttp chunks whenever `contentLength()` is unknown.
     */
    @Test
    fun aChunkedRequestBodyIsReassembledOnTheHttp1Path() {
        startProxy(protocols = listOf(Protocol.HTTP_1_1))
        origin.enqueue(MockResponse().setBody("ok"))

        val unknownLength = object : RequestBody() {
            override fun contentType() = "application/x-www-form-urlencoded".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("name=start&chunk=1")
            }
        }
        client.newCall(Request.Builder().url(originUrl("/v1/event")).post(unknownLength).build())
            .execute().close()

        val captured = awaitExchange { it.path == "/v1/event" }
        assertEquals(
            "OkHttp should have chunked a body of unknown length",
            "chunked",
            captured.requestHeaders["transfer-encoding"],
        )
        // Reassembled: the params only read back if the chunk loop got the whole body.
        assertEquals("start", captured.params["name"])
        assertEquals("1", captured.params["chunk"])

        assertEquals("name=start&chunk=1", origin.takeRequest(TIMEOUT_SECONDS, SECONDS)!!.body.readUtf8())
    }

    /** A host that is not intercepted keeps its TLS, and is still visible as a connection. */
    @Test
    fun anUninterceptedHostIsTunnelledButStillSeen() {
        // Intercept something this test never calls, so the origin falls through to tunnel.
        proxy = interposeProxy {
            certificateAuthority(ca)
            intercept("not-the-origin.example.com")
        }.apply { start() }
        client = clientThrough(proxy, trusting = originCertificate)
        origin.enqueue(MockResponse().setBody("direct"))

        val response = client.newCall(Request.Builder().url(originUrl("/v1/ping")).build()).execute()
        assertEquals("direct", response.use { it.body!!.string() })

        val connection = proxy.awaitConnection(TIMEOUT_SECONDS * 1_000) { it.host == LOOPBACK }
        assertNotNull("The tunnelled connection was not recorded", connection)
        assertEquals(false, connection!!.intercepted)
        // Tunnelled means the proxy never handshook, so it has no ALPN result to report.
        assertEquals(null, connection.clientProtocol)
        assertTrue("A tunnelled host must not yield contents", proxy.exchanges().isEmpty())
    }

    /** Intercepting without a CA is a configuration error, and says so. */
    @Test
    fun interceptingWithoutACertificateAuthorityFailsClearly() {
        val failure = runCatching {
            interposeProxy { intercept("api.example.com") }
        }.exceptionOrNull()

        assertNotNull("Expected a failure", failure)
        assertTrue(
            "Message should point at certificateAuthority(): ${failure?.message}",
            failure?.message?.contains("certificateAuthority") == true,
        )
    }

    private fun startProxy(
        protocols: List<Protocol> = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1),
        upstreamProtocol: UpstreamProtocol = UpstreamProtocol.MIRROR_CLIENT,
    ) {
        proxy = interposeProxy {
            certificateAuthority(ca)
            intercept(LOOPBACK)
            offer(protocols)
            upstream(upstreamProtocol)
            // The proxy's own upstream client has to trust this test's origin; the
            // default trusts only the platform anchors.
            upstreamClient {
                val originTrust = HandshakeCertificates.Builder()
                    .addTrustedCertificate(originCertificate.certificate)
                    .build()
                sslSocketFactory(originTrust.sslSocketFactory(), originTrust.trustManager)
            }
        }.apply { start() }
        client = clientThrough(proxy, trusting = null)
    }

    /**
     * A client pointed at the proxy. It trusts the capture CA, which is what makes a
     * minted leaf acceptable with nothing installed on the device; a tunnelled test
     * trusts the origin's own certificate instead, since the proxy never substitutes one.
     */
    private fun clientThrough(proxy: InterposeProxy, trusting: HeldCertificate?): OkHttpClient {
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(trusting?.certificate ?: ca.certificate())
            .build()
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, proxy.port)))
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .callTimeout(TIMEOUT_SECONDS, SECONDS)
            .build()
    }

    private fun originUrl(path: String) = "https://$LOOPBACK:${origin.port}$path"

    private fun awaitExchange(predicate: (CapturedExchange) -> Boolean): CapturedExchange =
        requireNotNull(proxy.awaitExchange(TIMEOUT_SECONDS * 1_000, predicate)) {
            "No matching exchange. Seen: ${proxy.exchanges()}"
        }

    private companion object {
        const val TAG = "ProxyFidelityTest"
        const val LOOPBACK = "127.0.0.1"
        const val BURST = 6
        const val TIMEOUT_SECONDS = 20L
    }
}
