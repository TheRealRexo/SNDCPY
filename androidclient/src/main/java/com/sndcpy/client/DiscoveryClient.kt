package com.sndcpy.client

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Listens for SNDCPY UDP discovery beacons on the LAN and reports every
 * server it finds.
 *
 * Beacons arrive on port [DISCOVERY_PORT] as a single line:
 *
 *     SNDCPYv1 ip=192.168.1.10 port=27183 name=SNDCPY-Foo proto=2 secure=1
 *
 * Start with [start]; call [onServerFound] to receive matches. Stop with
 * [stop] when done. Safe to call start/stop repeatedly.
 */
class DiscoveryClient {

    /** Fired (on the caller's thread) for each distinct server found. */
    var onServerFound: ((DiscoveredServer) -> Unit)? = null

    @Volatile
    private var running = false

    private var thread: Thread? = null

    private val seen = CopyOnWriteArrayList<DiscoveredServer>()

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(DISCOVERY_PORT))
                        soTimeout = SOCKET_TIMEOUT_MS)
                    }
                    val buf = ByteArray(512)
                    while (running) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val server = parse(text, packet.address.hostAddress) ?: continue
                        if (seen.none { it.ip == server.ip && it.port == server.port }) {
                            seen.add(server)
                            onServerFound?.invoke(server)
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "Discovery scan error", e)
                } finally {
                    socket?.close()
                }
                // Brief pause between scans so a stopped client doesn't spin.
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }, "sndcpy-discovery").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun parse(line: String, srcIp: String): DiscoveredServer? {
        if (!line.startsWith(BEACON_PREFIX)) return null
        var ip: String? = null
        var port: Int? = null
        var name: String? = null
        var proto: String? = null
        var secure: String? = null
        for (token in line.split(" ")) {
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            val k = token.substring(0, eq)
            val v = token.substring(eq + 1)
            when (k) {
                "ip" -> ip = v
                "port" -> port = v.toIntOrNull()
                "name" -> name = v
                "proto" -> proto = v
                "secure" -> secure = v
            }
        }
        val finalIp = ip ?: srcIp ?: return null
        val finalPort = port ?: return null
        return DiscoveredServer(
            ip = finalIp,
            port = finalPort,
            name = name ?: "SNDCPY",
            protocol = proto ?: "?",
            secure = secure == "1"
        )
    }

    companion object {
        private const val TAG = "DiscoveryClient"
        /** Must match [com.SNDCPY.server.discovery.UdpBroadcaster.DISCOVERY_PORT]. */
        const val DISCOVERY_PORT = 27184
        private const val SOCKET_TIMEOUT_MS = 1500

        /** Beacon prefix clients look for. */
        const val BEACON_PREFIX = "SNDCPYv1"
    }
}

/** A server advertised on the LAN. */
data class DiscoveredServer(
    val ip: String,
    val port: Int,
    val name: String,
    val protocol: String,
    val secure: Boolean
)