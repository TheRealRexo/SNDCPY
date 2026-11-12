package com.SNDCPY.server.discovery

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.Locale

/**
 * Periodically broadcasts a UDP beacon so LAN clients can discover the
 * server without typing an IP address.
 *
 * Beacons are sent to the broadcast address on the discovery port
 * ([DISCOVERY_PORT]). The payload is a single line of key=value pairs:
 *
 *     SNDCPYv1 ip=192.168.1.10 port=27183 name=SNDCPY-Foo proto=2 secure=1
 *
 * The beacon repeats every [INTERVAL_MS] milliseconds. The mDNS
 * advertisement ([NsdAdvertiser]) runs in parallel for clients that
 * prefer Bonjour; this class covers the simpler UDP-scan case.
 */
class UdpBroadcaster(
    private val controlPort: Int,
    private val serviceName: String,
    private val onStatus: (String) -> Unit
) {
    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                try {
                    val payload = buildPayload()
                    val data = payload.toByteArray(Charsets.UTF_8)
                    // Broadcast to 255.255.255.255 — reaches every interface
                    // on the local subnet without enumerating them.
                    val socket = DatagramSocket()
                    try {
                        socket.broadcast = true
                        val packet = DatagramPacket(
                            data, data.size,
                            InetSocketAddress(BROADCAST_ADDRESS, DISCOVERY_PORT)
                        )
                        socket.send(packet)
                    } finally {
                        socket.close()
                    }
                } catch (e: SocketException) {
                    if (running) onStatus("UDP broadcast error: ${e.message}")
                } catch (e: Exception) {
                    if (running) onStatus("UDP broadcast failed: ${e.message}")
                }
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "sndcpy-udp-broadcaster").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        onStatus("UDP broadcast stopped")
    }

    private fun buildPayload(): String {
        val ip = LanUtils.preferredIpv4() ?: "0.0.0.0"
        return "SNDCPYv1 ip=$ip port=$controlPort name=$serviceName " +
                "proto=${NsdAdvertiser.PROTOCOL_VERSION} secure=1"
    }

    companion object {
        const val DISCOVERY_PORT = 27184
        const val INTERVAL_MS = 3000L
        private const val BROADCAST_ADDRESS = "255.255.255.255"

        /** Marker string clients look for in a discovery response. */
        const val BEACON_PREFIX = "SNDCPYv1"
    }
}

/**
 * Tiny helper that picks a non-loopback IPv4 address for the local host.
 * Kept in its own file so the broadcaster can stay focused on sockets.
 */
object LanUtils {
    fun preferredIpv4(): String? {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addresses = ni.inetAddresses
            while (addresses.hasMoreElements()) {
                val a = addresses.nextElement()
                val s = a.hostAddress ?: continue
                if (!a.isLoopbackAddress && s.indexOf(':') < 0) return s
            }
        }
        return null
    }
}