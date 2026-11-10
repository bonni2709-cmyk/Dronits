package com.dronits.wotvpn.core

import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.annotation.StringRes
import com.dronits.wotvpn.R
import com.dronits.wotvpn.vpn.TunnelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

internal class CoreFailure(@StringRes val messageRes: Int) : IOException("VPN engine operation failed")

/** One owner for Xray, its authenticated local SOCKS5 server, tun2socks and the TUN fd. */
internal class ProxyManager(private val service: VpnService) : Closeable {
    private var tunnel: ParcelFileDescriptor? = null
    private var xrayAttempted = false
    private var relayAttempted = false
    private var closed = false

    suspend fun connect(configuration: String, establish: () -> ParcelFileDescriptor) = withContext(Dispatchers.IO) {
        check(!closed)
        val random = SecureRandom()
        var endpoint: SocksEndpoint? = null
        repeat(3) {
            if (endpoint == null) {
                currentCoroutineContext().ensureActive()
                val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val candidate = SocksEndpoint(port, "wot-${random.token()}", random.token())
                val bytes = try {
                    XrayConfigCompiler.compile(configuration, candidate).toByteArray(Charsets.UTF_8)
                } catch (_: ProxyConfigurationException) {
                    throw CoreFailure(R.string.error_config_settings)
                }
                val code = try {
                    xrayAttempted = true
                    NativeXray.start(bytes, service)
                } catch (_: LinkageError) {
                    throw CoreFailure(R.string.error_native_unavailable)
                } finally {
                    bytes.fill(0)
                }
                when (code) {
                    0 -> endpoint = candidate
                    1 -> throw CoreFailure(R.string.error_config_settings)
                    3 -> Unit // Port was taken between reservation and bind; retry with a fresh port.
                    else -> throw CoreFailure(R.string.error_core_start)
                }
            }
        }
        val socks = endpoint ?: throw CoreFailure(R.string.error_core_start)
        try {
            Socks5Probe.verifyUdp(socks)
        } catch (_: IOException) {
            throw CoreFailure(R.string.error_socks_udp)
        }
        currentCoroutineContext().ensureActive()
        // Only establish once Xray and its authenticated UDP association are ready.
        val fd = establish()
        tunnel = fd
        currentCoroutineContext().ensureActive()
        val relayConfig = tunnelConfiguration(socks).toByteArray(Charsets.UTF_8)
        try {
            relayAttempted = true
            if (!NativeTunnel.start(relayConfig, fd.fd)) throw CoreFailure(R.string.error_relay_start)
        } catch (_: LinkageError) {
            throw CoreFailure(R.string.error_native_unavailable)
        } finally {
            relayConfig.fill(0)
        }
        currentCoroutineContext().ensureActive()
        if (!isRunning()) throw CoreFailure(R.string.error_core_stopped)
    }

    fun isRunning(): Boolean = !closed && tunnel != null && NativeXray.isRunning() && NativeTunnel.isRunning()

    /** Called on IO after connect has completed/cancelled. Native relay joins before the fd is closed. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            if (relayAttempted) NativeTunnel.stop()
        } catch (_: LinkageError) {
            // A load failure cannot own a native worker.
        } finally {
            try { tunnel?.close() } catch (_: IOException) { }
            tunnel = null
            if (xrayAttempted) {
                try { NativeXray.stop() } catch (_: LinkageError) { }
            }
        }
    }

    private fun SecureRandom.token(): String = ByteArray(24).also { nextBytes(it) }
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    internal companion object {
        fun tunnelConfiguration(endpoint: SocksEndpoint): String = """
            tunnel:
              mtu: ${TunnelSettings.MTU}
              ipv4: '${TunnelSettings.IPV4}'
              ipv6: '${TunnelSettings.IPV6}'
            socks5:
              address: 127.0.0.1
              port: ${endpoint.port}
              udp: 'udp'
              udp-address: 127.0.0.1
              username: '${endpoint.username}'
              password: '${endpoint.password}'
            misc:
              task-stack-size: 86016
              tcp-buffer-size: 65536
              max-session-count: 1024
              connect-timeout: 10000
              tcp-read-write-timeout: 900000
              udp-read-write-timeout: 300000
              log-file: null
              log-level: error
        """.trimIndent()
    }
}
