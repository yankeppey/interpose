# Interpose example

A small app with **two HTTP stacks** — its own `OkHttpClient`, built in
`Application.onCreate`, and a `WebView` — and one instrumented test that asserts on both.

```bash
./gradlew :example:connectedDebugAndroidTest
```

The test needs a device or emulator with network: it drives the app against the real
`example.com` rather than a local server, so what it asserts is a genuine HTTP/2
negotiation. (Interpose's own suite is self-contained and uses `MockWebServer`.)

## What to copy from here

| | |
|---|---|
| [`ExampleInterposeRunner`](src/androidTest/kotlin/eu/buney/interpose/example/ExampleInterposeRunner.kt) | where the CA goes, and why it is a provider rather than a value |
| [`build.gradle.kts`](build.gradle.kts) | `testInstrumentationRunner`, and Interpose at `androidTest` scope only |
| [`src/debug/`](src/debug) | the trust anchor, in a debug-only network security config and a debug-only manifest overlay |
| [`ExampleApplication`](src/main/kotlin/eu/buney/interpose/example/ExampleApplication.kt) | the client that is built before any test code runs — the reason the runner exists |
| [`ExampleCaptureTest`](src/androidTest/kotlin/eu/buney/interpose/example/ExampleCaptureTest.kt) | the whole of the test-side API: `Interpose.proxy().awaitExchange { … }` |

## About the committed CA

`src/androidTest/assets/interpose-ca.pem` contains a **private key, in public, on
purpose**. It is the shape the documentation describes — one CA committed twice, the
certificate where the app can trust it and the key where the test can sign with it — and
with the anchor fixed at build time there is no other way to do it.

It is safe here and it would be safe in your repository too, for the same two reasons:

- It only ever appears under `<debug-overrides>`, which the platform ignores unless the
  APK is debuggable. A release build of this app does not trust it.
- It signs nothing but the certificates a proxy on `127.0.0.1` presents to the app that
  started it.

Treat it as public, because it is: **do not reuse this CA.** Mint your own, once, with
`CertificateAuthority.generate().toPem()`.
