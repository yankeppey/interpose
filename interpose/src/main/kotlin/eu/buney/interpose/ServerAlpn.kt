package eu.buney.interpose

import android.os.Build
import okhttp3.Protocol
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import javax.net.ssl.SSLSocket

/**
 * Server-side ALPN, done directly rather than through `okhttp3.internal.platform.Platform`.
 *
 * It started as two `Platform` calls, which was the obvious choice while they worked. They
 * do not survive okhttp 5: `configureTlsExtensions` gained a fourth parameter
 * (`echConfigList`, for Encrypted Client Hello) with no overload left behind, so code
 * compiled against 4.x hits `NoSuchMethodError` — on **every** intercepted connection, not
 * just h2 ones, and before [InterposeProxy]'s codec probe can decide anything.
 *
 * Reimplementing it costs ~30 lines and removes `Platform` from the proxy's surface
 * entirely, which leaves only the h2 codec behind a guard that can degrade gracefully.
 * There is no cleverness here, and nothing okhttp-specific: it is the same two mechanisms
 * okhttp picks between, chosen the same way.
 */
internal object ServerAlpn {

    /** Offers [protocols] to the client, in order of preference. */
    fun configure(socket: SSLSocket, protocols: List<Protocol>) {
        val names = alpnNames(protocols)
        if (Build.VERSION.SDK_INT >= API_APPLICATION_PROTOCOLS) {
            // Public since API 29, and Conscrypt honours it in server mode: it selects the
            // first of ours the client also offered.
            socket.sslParameters = socket.sslParameters.apply { applicationProtocols = names.toTypedArray() }
            return
        }
        Conscrypt.instance?.setAlpnProtocols(socket, names)
    }

    /** The negotiated protocol, or null if ALPN did not settle on one. */
    fun selected(socket: SSLSocket): String? {
        if (Build.VERSION.SDK_INT >= API_APPLICATION_PROTOCOLS) {
            // Empty rather than null when the socket was never given a protocol list.
            return socket.applicationProtocol?.takeIf { it.isNotEmpty() }
        }
        return Conscrypt.instance?.selectedProtocol(socket)
    }

    /** `HTTP_1_0` has no ALPN identifier, so it cannot be offered. */
    private fun alpnNames(protocols: List<Protocol>) =
        protocols.filter { it != Protocol.HTTP_1_0 }.map { it.toString() }

    /**
     * API 24–28, where `SSLParameters.applicationProtocols` does not exist yet and the
     * only way in is the platform's own TLS implementation.
     *
     * These two methods are AOSP internals, which sounds worse than it is: they are
     * non-SDK but **light greylist** (warn, allow) at API 28, measured on a passing run,
     * and every okhttp in the world reaches them the same way, so they cannot move without
     * breaking the ecosystem. Deliberately *not* the Play Services Conscrypt — nothing
     * here installs that provider, and depending on it would make the result vary with
     * whether a device has GMS.
     */
    private class Conscrypt private constructor(
        private val socketClass: Class<*>,
        private val setAlpnProtocols: Method,
        private val getAlpnSelectedProtocol: Method,
    ) {

        fun setAlpnProtocols(socket: SSLSocket, names: List<String>) {
            if (!socketClass.isInstance(socket)) return
            runCatching { setAlpnProtocols.invoke(socket, lengthPrefixed(names)) }
        }

        fun selectedProtocol(socket: SSLSocket): String? {
            if (!socketClass.isInstance(socket)) return null
            return runCatching {
                (getAlpnSelectedProtocol.invoke(socket) as ByteArray?)?.toString(Charsets.UTF_8)
            }.getOrNull()
        }

        /** The ALPN wire format: each name as one length byte then its ASCII bytes. */
        private fun lengthPrefixed(names: List<String>): ByteArray {
            val out = ByteArrayOutputStream()
            names.forEach { name ->
                out.write(name.length)
                out.write(name.toByteArray(Charsets.US_ASCII))
            }
            return out.toByteArray()
        }

        companion object {
            val instance: Conscrypt? by lazy {
                runCatching {
                    val socketClass = Class.forName("com.android.org.conscrypt.OpenSSLSocketImpl")
                    Conscrypt(
                        socketClass,
                        socketClass.getMethod("setAlpnProtocols", ByteArray::class.java),
                        socketClass.getMethod("getAlpnSelectedProtocol"),
                    )
                }.getOrNull()
            }
        }
    }

    private const val API_APPLICATION_PROTOCOLS = 29
}
