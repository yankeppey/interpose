# Interpose

[![Maven Central](https://img.shields.io/maven-central/v/eu.buney.interpose/interpose-android?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/eu.buney.interpose/interpose-android)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
![Stability: Experimental](https://img.shields.io/badge/stability-experimental-orange.svg)

In-process HTTP capture for Android instrumented tests.

Interpose runs a TLS-terminating proxy **inside the app under test**, so a test can read
every HTTP request the app makes — including from the WebView, and including from code you
do not own. No device proxy to configure, no certificate to install by hand, no external
process, no `VpnService`.

It keeps HTTP/2.

> ## ⚠️ Experimental — `0.1.0`
>
> First release. **There are no API-stability guarantees** — anything here may change or be
> removed between versions, without deprecation. The blast radius is a test suite rather
> than a shipped app, since this is a test-scope dependency that cannot reach a release
> build; the part most likely to move is the HTTP/2 path, which drives
> `okhttp3.internal.http2` and so inherits that API's lack of any compatibility promise.

```kotlin
androidTestImplementation("eu.buney.interpose:interpose-android:0.1.0")
```

```kotlin
// In your AndroidJUnitRunner subclass, before super.onCreate:
Interpose.install {
    certificateAuthority { CertificateAuthority.fromPem(readCaPem()) }
    intercept("api.example.com", "analytics.example.com")
}
```

```kotlin
@Test fun theAppRegistersTheSession() {
    val request = Interpose.proxy().awaitExchange(timeoutMs = 10_000) {
        it.host == "api.example.com" && it.path == "/v1/session"
    }

    assertThat(request).isNotNull()
    assertThat(request!!.params["device_id"]).isNotEmpty()
    assertThat(request.status).isEqualTo(200)
}
```

All of that assembled and running — app, CA, trust config, runner, test — is in
[`example/`](example).

## Why this exists

If every HTTP call your app makes goes through an `OkHttpClient` you build yourself, you do
not need this — add an interceptor. [Chucker][chucker] and friends sit above TLS, cost
nothing, and show you everything.

The gap opens when your app has **more than one HTTP stack**. A WebView. A media player. An
image loader on `HttpURLConnection`. A third-party SDK whose client you never see. An
interceptor shows you the traffic you already control, and says nothing about the rest —
which is often exactly the traffic you wanted to assert on.

To see *all* of it you have to be an endpoint, which means terminating TLS. And here every
embeddable option ran out: [mitmproxy][mitmproxy] is a separate process,
[MockServer][mockserver] needs Java 17 and a Netty native library with no Android ABI,
[NetBare][netbare] did it properly but has been dead since 2022 and needed a `VpnService`
with its consent dialog. The one in-process recipe that circulated —
[LittleProxy-mitm][littleproxy]'s "a single class based on `okhttp-tls`" — is HTTP/1.1
only, and that was the ceiling everybody stopped at.

**Stopping at HTTP/1.1 is not a detail.** Most production hosts answer `h2`. A proxy that
offers no ALPN silently downgrades the app for the duration of the test, so you are
asserting against a transport your app never actually uses — and an HTTP/2-specific defect,
request loss under multiplexing being the obvious one, cannot even be *reproduced*, let
alone caught.

Interpose offers `h2` over ALPN, serves it with OkHttp's own HTTP/2 codec, and by default
offers the origin exactly what the app negotiated — so **the protocol changes in neither
direction**.

## How it works

```
app ──CONNECT──▶ Interpose ──────▶ origin
    ◀── h2 ───▶            ◀─ h2 ─▶      (ALPN negotiated on both legs)
```

Two calls point the process at the proxy, because Android has two proxy worlds:

- `ProxySelector.setDefault` covers everything built on `java.net` — OkHttp (when the
  client sets no proxy of its own), `HttpURLConnection`, and the many libraries layered on
  those two.
- `WebView` runs its own network stack and ignores the above entirely, so it gets
  `androidx.webkit`'s `ProxyController` separately.

A `CONNECT` to an intercepted host is answered, TLS is terminated with a certificate minted
for that host on the spot, and the requests inside are parsed, recorded and replayed
upstream. **Everything else is relayed byte for byte with its TLS untouched** — still
visible as a connection, just not readable. That is the default, and it is worth keeping:
interception is the only part that can affect the app under test, and an intercepted
response is buffered whole, which is fine for API calls and wrong for video.

None of the HTTP/2 implementation is Interpose's. OkHttp ships a complete one and
`MockWebServer` has been driving it in server mode on Android for years; Interpose is the
`CONNECT`-handling, certificate-minting, forwarding shell around it.

## Install it before `Application.onCreate`

This is the one constraint that will bite you. OkHttp resolves the default `ProxySelector`
**once**, when a client is constructed — so an app that builds its HTTP client during
`Application.onCreate` has already decided before any `@Before` runs.

`Instrumentation.onCreate` happens before `callApplicationOnCreate`, which is the last
point at which the decision is still open. `InterposeRunner` is that hook:

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "eu.buney.interpose.InterposeRunner"
    }
}
```

It does nothing unless asked, so your ordinary test runs are unaffected:

```bash
adb shell am instrument -w \
  -e interpose true \
  -e interpose.intercept api.example.com+analytics.example.com \
  com.example.app.test/eu.buney.interpose.InterposeRunner
```

Interception also needs a CA, which cannot come from a command-line argument — so subclass
`InterposeRunner` and override `configure` for anything beyond watching hosts.

## Trusting the certificate

Interception means answering as the host the app asked for, which needs a certificate the
app accepts. Nothing is installed on the device and no user-store CA is involved: the
certificate goes in a **debug-only** network security config.

```xml
<!-- src/debug/res/xml/network_security_config.xml -->
<network-security-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="@raw/interpose_ca"/>
            <certificates src="system"/>
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

`debug-overrides` is ignored in a release build, so this cannot weaken a shipped app.

The anchor has to be fixed at build time, so the usual shape is one CA committed twice —
the certificate in `src/debug/res/raw/` for the app to trust, the certificate *and* key
where the test can read it. Mint the pair once:

```kotlin
println(CertificateAuthority.generate().toPem())   // key block first, then certificate
```

A test that drives its own HTTP client rather than the app's needs none of this — it can
trust a freshly `generate()`d CA directly.

[`example/`](example) is this whole arrangement as working code: the committed pair, the
debug-only trust config, and a runner that loads the CA from test assets.

## Configuration

```kotlin
Interpose.install {
    certificateAuthority { CertificateAuthority.fromPem(pem) }

    // TLS terminated and contents readable. A host matches itself and its subdomains.
    // Everything else is tunnelled: visible as a connection, not readable.
    intercept("api.example.com")

    // What to offer the app over ALPN. Default: HTTP_2, HTTP_1_1.
    offer(Protocol.HTTP_2, Protocol.HTTP_1_1)

    // What to offer the origin. Default: MIRROR_CLIENT — whatever the app chose, so the
    // proxy changes nothing. PREFER_HTTP_2 always reaches for h2, which is how you ask
    // "would this host even do h2?" about a client that cannot offer it.
    upstream(UpstreamProtocol.MIRROR_CLIENT)

    // Applied on top of the proxy's own defaults.
    upstreamClient { connectTimeout(5, TimeUnit.SECONDS) }
}
```

What you get back, per request:

| | |
|---|---|
| `method`, `url`, `host`, `path`, `status` | the basics |
| `params` | query merged with a form-encoded body, so an assertion does not care which shape the client picked |
| `requestHeaders`, `responseHeaders` | `okhttp3.Headers`, so lookups are case-insensitive |
| `requestBody`, `responseBody` | decoded as UTF-8 for reading; the bytes forwarded upstream are untouched |
| `clientProtocol`, `upstreamProtocol` | `h2` or `http/1.1`, per leg — assert on these if fidelity matters, because a downgrade is invisible in the contents |

## Limits

- **HTTP/3 and QUIC are invisible.** They are UDP; a TCP proxy is structurally blind to
  them. This is permanent, not a to-do.
- **Anything that ignores `ProxySelector` is invisible.** Most things honour it. For
  system-wide capture you want [PCAPdroid][pcapdroid] and a `VpnService`, which is a
  deliberately different trade.
- **Certificate pinning defeats interception**, by design. Relax it in your debug build or
  leave that host tunnelled.
- **Intercepted responses are buffered whole.** Keep `intercept` to the hosts whose
  contents you actually assert on; do not point it at media.
- **The suite runs on API 24, 28 and 36**, on every push. Below API 29 server-side ALPN
  goes through the platform's own TLS stack rather than a modern Conscrypt, which is why
  `minSdk` is 24 and why the floor is tested rather than assumed.
- **No WebSocket or `CONNECT`-upgrade handling** yet; such a connection is tunnelled.

## OkHttp versions

Interpose declares a **floor**, not a pin — `com.squareup.okhttp3:okhttp:4.6.0`. A plain
Gradle version is a minimum, so if your app is on a newer OkHttp, Gradle resolves up and
Interpose compiles and runs against yours. That matters because Gradle's consistent
resolution pins the `androidTest` classpath to whatever the app under test already uses.

4.6.0 is measured, not guessed: it is the oldest release this compiles against.

The HTTP/2 path calls `okhttp3.internal.http2`, which carries no compatibility promise. If
a future OkHttp changes it, Interpose notices before accepting any connection and stops
offering `h2` rather than failing — intercepted traffic degrades to HTTP/1.1 and keeps
working, loudly logged. Interpose's own test suite asserts `h2` on both legs, so the
regression is caught here rather than in your build.

## License

Apache 2.0 — see [LICENSE](LICENSE).

[chucker]: https://github.com/ChuckerTeam/chucker
[mitmproxy]: https://github.com/mitmproxy/mitmproxy
[mockserver]: https://github.com/mock-server/mockserver
[netbare]: https://github.com/MegatronKing/NetBare-Android
[littleproxy]: https://github.com/ganskef/LittleProxy-mitm
[pcapdroid]: https://github.com/emanuele-f/PCAPdroid
