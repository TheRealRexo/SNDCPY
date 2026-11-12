package com.SNDCPY.server.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.SNDCPY.server.BuildConfig
import com.SNDCPY.server.discovery.LocalDiscoveryPrototype
import com.SNDCPY.server.discovery.NsdAdvertiser
import com.SNDCPY.server.input.BasicInputInjector
import com.SNDCPY.server.input.SndcpyAccessibilityService
import com.SNDCPY.server.protocol.Envelope
import com.SNDCPY.server.security.PairingManager
import com.SNDCPY.server.server.Session
import com.SNDCPY.server.server.SessionManager
import com.SNDCPY.server.server.TcpControlServer
import com.SNDCPY.server.service.CaptureService
import com.SNDCPY.server.streaming.StreamingBus
import com.SNDCPY.server.telemetry.TelemetryCollector

/**
 * Orchestrates every SNDCPY server subsystem:
 * discovery, secure pairing, the TCP control server, streaming and input.
 */
class RemoteControlRuntime(
    context: Context,
    private val statusSink: (String) -> Unit
) : TcpControlServer.Listener {

    private val appContext = context.applicationContext
    private val sessionManager = SessionManager()
    private val pairingManager = PairingManager(appContext)
    private val discovery = LocalDiscoveryPrototype()
    private val nsdAdvertiser = NsdAdvertiser(appContext)
    private val udpBroadcaster = UdpBroadcaster(
        controlPort = PORT,
        serviceName = NsdAdvertiser.defaultServiceName(),
        onStatus = ::report
    )
    private val inputInjector = BasicInputInjector()
    private val telemetry = TelemetryCollector(
        sessionCountProvider = sessionManager::activeSessionCount,
        onTelemetryLine = ::report
    )

    private val server = TcpControlServer(
        port = PORT,
        sessionManager = sessionManager,
        pairingManager = pairingManager,
        listener = this
    )

    fun start() {
        SndcpyAccessibilityService.onAvailabilityChanged = { available ->
            report(if (available) "Remote input engine connected" else "Remote input engine disconnected")
        }
        report("SNDCPY server v${BuildConfig.VERSION_NAME} (protocol ${Envelope.PROTOCOL_VERSION})")
        report("Server fingerprint: ${pairingManager.serverFingerprint.take(16)}…")
        report(discovery.advertise(PORT))
        StreamingBus.start()
        telemetry.start()
        server.start()
        nsdAdvertiser.start(PORT)
        report("mDNS service advertised (${NsdAdvertiser.SERVICE_TYPE})")
        udpBroadcaster.start()
        report("UDP beacon on port ${UdpBroadcaster.DISCOVERY_PORT} (LAN discovery)")
        report(
            if (inputInjector.canInject()) "Remote input ready (TAP, SWIPE, SCROLL, HOME, BACK, RECENTS, KEY:*)"
            else "Remote input pending — enable the accessibility service"
        )
        report("Awaiting paired client connections…")
    }

    fun stop() {
        stopCapture()
        nsdAdvertiser.stop()
        udpBroadcaster.stop()
        server.stop()
        telemetry.stop()
        StreamingBus.stop()
        SndcpyAccessibilityService.onAvailabilityChanged = null
        report("Runtime stopped")
    }

    /** Issues a fresh pairing code and surfaces it in the UI. */
    fun issuePairingCode(): String {
        val code = pairingManager.issuePairingCode()
        report("Pairing code: $code (valid ${PairingManager.PAIRING_CODE_TTL_MINUTES} min)")
        return code
    }

    /** Starts the foreground capture service once the user approved projection. */
    fun onProjectionApproved(resultCode: Int, data: Intent) {
        report("Screen capture approved — starting foreground service…")
        ContextCompat.startForegroundService(appContext, CaptureService.intent(appContext, resultCode, data))
    }

    fun stopCapture() {
        appContext.stopService(Intent(appContext, CaptureService::class.java))
        report("Screen capture stopped")
    }

    fun injectInput(command: String): Boolean {
        val accepted = inputInjector.inject(command)
        report(if (accepted) "Injected command: $command" else "Rejected command: $command")
        return accepted
    }

    private val whitespaceRegex = Regex("\\s+")

    private fun report(message: String) = statusSink(message)

    // ---- TcpControlServer.Listener ----

    override fun onSessionEstablished(session: Session) {
        report("Secure session #${session.id} established with ${session.remoteAddress}")
    }

    override fun onSessionEnded(session: Session) {
        report("Session #${session.id} (${session.remoteAddress}) ended")
    }

    override fun onCommandReceived(session: Session, command: String): Boolean {
        handleConfigCommand(command)?.let {
            report("[#${session.id}] Config: $it")
            return true
        }
        if (!inputInjector.canInject()) {
            report("[#${session.id}] Rejected (input engine disabled): $command")
            return false
        }
        val accepted = inputInjector.inject(command)
        report("[#${session.id}] ${if (accepted) "Injected" else "Rejected"}: $command")
        return accepted
    }

    /** Handles SET_BITRATE / SET_FPS control commands; returns a status line if handled. */
    private fun handleConfigCommand(command: String): String? {
        val tokens = command.trim().uppercase().split(whitespaceRegex).filter { it.isNotEmpty() }
        when (tokens.getOrNull(0)) {
            "SET_BITRATE" -> {
                val value = tokens.getOrNull(1)?.toIntOrNull() ?: return null
                RuntimeConfig.videoBitrateBitsPerSecond = value
                return "bitrate=${RuntimeConfig.videoBitrateBitsPerSecond}bps (applies on next capture start)"
            }
            "SET_FPS" -> {
                val value = tokens.getOrNull(1)?.toIntOrNull() ?: return null
                RuntimeConfig.frameRate = value
                return "fps=${RuntimeConfig.frameRate} (applies on next capture start)"
            }
            "GET_CONFIG" -> {
                return "bitrate=${RuntimeConfig.videoBitrateBitsPerSecond}, fps=${RuntimeConfig.frameRate}, max=${RuntimeConfig.maxWidth}x${RuntimeConfig.maxHeight}"
            }
            else -> return null
        }
    }

    override fun onPairingCodeIssued(code: String) {
        report("New device requesting pair-in. Code: $code")
    }

    override fun onStatus(status: String) {
        report(status)
    }

    companion object {
        const val PORT = 27183
    }
}
