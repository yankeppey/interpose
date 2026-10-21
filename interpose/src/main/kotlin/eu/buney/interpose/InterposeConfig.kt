package eu.buney.interpose

import okhttp3.OkHttpClient
import okhttp3.Protocol

/** What the proxy offers the origin over ALPN, relative to what the app negotiated. */
public enum class UpstreamProtocol {

    /**
     * Whatever the app chose, so the proxy changes the protocol in neither direction.
     *
     * The default, because transparency is the property worth having. An app on
     * `HttpURLConnection` has no HTTP/2 to offer and therefore talks 1.1 in production;
     * reaching its origin over h2 would exercise a path that never happens for real.
     * Upgrading is the same kind of infidelity as the downgrade this library exists to
     * remove, only less harmful.
     */
    MIRROR_CLIENT,

    /**
     * Always offer HTTP/2 upstream, whatever the app managed.
     *
     * For exercising an origin over h2 regardless of the client's limits, or for
     * answering "would this host even do h2?" about a host whose client cannot ask.
     */
    PREFER_HTTP_2,
}

/**
 * Configuration for an [InterposeProxy]. Build one with [interposeProxy], or let
 * [Interpose.install] do it.
 *
 * ```kotlin
 * interposeProxy {
 *     certificateAuthority(CertificateAuthority.fromPem(pem))
 *     intercept("api.example.com", "analytics.example.com")  // TLS terminated, readable
 *     offer(Protocol.HTTP_2, Protocol.HTTP_1_1)            // app leg, over ALPN
 *     upstream(UpstreamProtocol.MIRROR_CLIENT)             // origin leg
 *     upstreamClient { connectTimeout(5, TimeUnit.SECONDS) }
 * }
 * ```
 */
public class InterposeConfig internal constructor() {

    internal val interceptHosts = mutableSetOf<String>()

    internal var protocols: List<Protocol> = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        private set

    internal var upstreamProtocol: UpstreamProtocol = UpstreamProtocol.MIRROR_CLIENT
        private set

    internal var certificateAuthorityProvider: (() -> CertificateAuthority)? = null
        private set

    private var upstreamClientBlock: (OkHttpClient.Builder.() -> Unit)? = null

    /**
     * Hosts whose TLS is terminated, so their request contents become readable. A host
     * matches itself and its subdomains.
     *
     * Additive. Everything not listed is relayed byte for byte with its TLS intact, which
     * is also what listing nothing means — you still see those connections in
     * [InterposeProxy.connections], just not their contents.
     *
     * Selective on purpose. Interception is what makes request parameters assertable, and
     * it is also the only part that can affect the app under test, so the shorter this
     * list is the smaller the blast radius. It is also the part that costs: an intercepted
     * response is buffered whole, which is fine for API calls and wrong for video.
     */
    public fun intercept(vararg hosts: String) {
        interceptHosts += hosts
    }

    /** As [intercept], for a list that was computed rather than written out. */
    public fun intercept(hosts: Collection<String>) {
        interceptHosts += hosts
    }

    /**
     * The CA to sign per-host certificates with. Required if anything is intercepted —
     * see [CertificateAuthority] for how to get the app to trust it.
     */
    public fun certificateAuthority(ca: CertificateAuthority) {
        certificateAuthorityProvider = { ca }
    }

    /**
     * As [certificateAuthority], but resolved on first use instead of now.
     *
     * Worth reaching for more often than it looks. Installation has to happen inside
     * `Instrumentation.onCreate`, and `InstrumentationRegistry` is not populated yet at
     * that point — so loading a CA from test assets eagerly here fails with "No
     * instrumentation registered!". Deferring moves the read to the first intercepted
     * connection, by which time everything is up.
     *
     * ```kotlin
     * certificateAuthority {
     *     CertificateAuthority.fromPem(
     *         InstrumentationRegistry.getInstrumentation().context.assets
     *             .open("ca.pem").bufferedReader().use { it.readText() }
     *     )
     * }
     * ```
     */
    public fun certificateAuthority(provider: () -> CertificateAuthority) {
        certificateAuthorityProvider = provider
    }

    /**
     * What to offer the app over ALPN on an intercepted connection, in order of
     * preference. Replaces the default of `HTTP_2, HTTP_1_1`.
     *
     * Narrow it to `HTTP_1_1` alone to see how the app behaves without HTTP/2 at all.
     */
    public fun offer(vararg protocols: Protocol): Unit = offer(protocols.toList())

    /** As [offer], for a list that was computed rather than written out. */
    public fun offer(protocols: List<Protocol>) {
        // Without 1.1 in the list, a client that cannot do h2 fails during the handshake,
        // which surfaces as an unhelpful certificate error.
        require(Protocol.HTTP_1_1 in protocols) {
            "protocols must contain http/1.1 as a fallback: $protocols"
        }
        this.protocols = protocols.toList()
    }

    /** Whether the origin leg follows the app's protocol or always reaches for HTTP/2. */
    public fun upstream(protocol: UpstreamProtocol) {
        upstreamProtocol = protocol
    }

    /**
     * Adds to the client the proxy uses to reach origins — to trust a test server's
     * certificate, or to shorten a timeout.
     *
     * Applied *on top of* the proxy's own defaults, so the settings it depends on are
     * already in place and need not be restated. One is worth knowing about before
     * overriding it: the upstream client must not consult the default `ProxySelector`,
     * because that is this proxy, and it would hand its own requests back to itself. The
     * default is therefore `Proxy.NO_PROXY`. Replacing that with an explicit proxy is
     * fine; clearing it is not.
     */
    public fun upstreamClient(configure: OkHttpClient.Builder.() -> Unit) {
        upstreamClientBlock = configure
    }

    internal fun buildUpstreamClient(): OkHttpClient =
        UpstreamRelay.defaultClient().newBuilder()
            .apply { upstreamClientBlock?.invoke(this) }
            .build()

    /**
     * Checked now, resolved later: a missing CA is a configuration mistake and should
     * surface at `install`, while *loading* one often cannot happen that early.
     */
    internal fun certificateAuthorityOrNull(): Lazy<CertificateAuthority>? {
        if (interceptHosts.isEmpty()) return null
        val provider = checkNotNull(certificateAuthorityProvider) {
            "intercept() names ${interceptHosts.size} host(s) but no certificateAuthority() " +
                "was given. Terminating TLS means presenting a certificate for those hosts; " +
                "see CertificateAuthority for how to mint one and have the app trust it."
        }
        return lazy(provider)
    }
}

/**
 * Builds an [InterposeProxy]. Call [InterposeProxy.start] to begin listening, or use
 * [Interpose.install], which also points the process at it.
 */
public fun interposeProxy(configure: InterposeConfig.() -> Unit = {}): InterposeProxy =
    InterposeProxy(InterposeConfig().apply(configure))
