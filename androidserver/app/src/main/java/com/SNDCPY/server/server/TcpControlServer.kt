package com.SNDCPY.server.server

import android.util.Log
import com.SNDCPY.server.protocol.Envelope
import com.SNDCPY.server.security.CryptoEngine
import com.SNDCPY.server.security.PairingManager
import com.SNDCPY.server.streaming.StreamingBus
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.SecretKey

/**
 * TCP control server implementing the SNDCPY secure handshake.
 *
 * Handshake (plain text, one token stream):
 *   client -> HELLO SNDCPY <clientPublicKeyB64> <clientNonceB64>
 *
 *   paired client:
 *     server -> SECURE <serverPublicKeyB64> <serverNonceB64>
 *
 *   unknown client:
 *     server -> PAIRING_REQUIRED <serverPublicKeyB64> <serverNonceB64>
 *     client -> PAIR <6-digit-code>
 *     server -> PAIRED | REJECTED
 *
 * Both sides then derive an AES-256-GCM session key via ECDH + HKDF and
 * switch to [SecureFrameChannel] framing for all further traffic.
 */
class TcpControlServer(
    private val port: Int,
    private val sessionManager: SessionManager,
    private val pairingManager: PairingManager,
    private val listener: Listener
) {

    interface Listener {
        fun onSessionEstablished(session: Session)
        fun onSessionEnded(session: Session)
        fun onCommandReceived(session: Session, command: String): Boolean
        fun onPairingCodeIssued(code: String)
        fun onPromptPairingRequested(fingerprint: String)
        fun onStatus(status: String)
    }

    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sndcpy-tcp-accept").apply { isDaemon = true }
    }
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "sndcpy-session").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        acceptExecutor.execute {
            try {
                ServerSocket(port).use { socket ->
                    serverSocket = socket
                    socket.reuseAddress = true
                    socket.soTimeout = ACCEPT_TIMEOUT_MS
                    Log.i(TAG, "TCP control server started on port $port")
                    listener.onStatus("TCP control server listening on port $port")

                    while (running) {
                        try {
                            val client = socket.accept()
                            clientExecutor.execute { handleClient(client) }
                        } catch (_: SocketTimeoutException) {
                            // Polling timeout so stop() can break the loop quickly.
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "TCP server failed", e)
                    listener.onStatus("TCP server error: ${e.message}")
                }
            } finally {
                running = false
                serverSocket = null
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
            // Ignore shutdown exception.
        }
        clientExecutor.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        var session: Session? = null
        try {
            client.use { socket ->
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                socket.tcpNoDelay = true
                val remoteAddress = socket.inetAddress?.hostAddress ?: "unknown"

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val hello = reader.readLine()?.trim().orEmpty()
                val helloParts = hello.split(WHITESPACE_REGEX)
                if (helloParts.size != 4 || helloParts[0] != "HELLO" || helloParts[1] != PROTOCOL_NAME) {
                    writer.println("ERROR expected 'HELLO $PROTOCOL_NAME <key> <nonce>'")
                    return
                }

                val clientPublicKey = try {
                    CryptoEngine.decodePublicKey(helloParts[2])
                } catch (_: Exception) {
                    writer.println("ERROR malformed client public key")
                    return
                }
                val clientNonce = try {
                    CryptoEngine.decodeBytes(helloParts[3])
                } catch (_: Exception) {
                    writer.println("ERROR malformed client nonce")
                    return
                }

                val clientFingerprint = CryptoEngine.fingerprint(clientPublicKey)
                val serverNonce = CryptoEngine.randomNonce()

                if (!pairingManager.isPaired(clientFingerprint)) {
                    val pairingCode = pairingManager.issuePairingCode()
                    listener.onPairingCodeIssued(pairingCode)
                    writer.println("PAIRING_REQUIRED ${CryptoEngine.encodePublicKey(pairingManager.serverKeyPair.public)} ${CryptoEngine.encodeBytes(serverNonce)}")

                    var paired = false
                    var promptFingerprint: String? = null
                    for (attempt in 1..PAIRING_ATTEMPTS) {
                        val line = reader.readLine()?.trim().orEmpty()
                        val parts = line.split(WHITESPACE_REGEX)
                        if (parts.size == 2 && parts[0] == "PAIR") {
                            // Classic pairing with a 6-digit code.
                            if (pairingManager.verifyPairingCode(parts[1])) {
                                paired = true
                                break
                            }
                        } else if (parts.size == 2 && parts[0] == "PAIR" && parts[1] == "PROMPT") {
                            // Bluetooth-style prompt pairing: both sides must confirm.
                            if (pairingManager.requestPromptPairing(clientFingerprint)) {
                                listener.onPromptPairingRequested(clientFingerprint)
                                val approved = pairingManager.waitForPromptDecision()
                                if (!approved) {
                                    writer.println("REJECTED")
                                    listener.onStatus("Prompt pairing rejected for $remoteAddress")
                                    return
                                }
                                writer.println("PROMPT_APPROVED")
                                // Wait for the client-side confirmation.
                                val confirm = reader.readLine()?.trim().orEmpty()
                                val confirmParts = confirm.split(WHITESPACE_REGEX)
                                if (confirmParts.size == 2 && confirmParts[0] == "PAIR" && confirmParts[1] == "CONFIRM") {
                                    promptFingerprint = clientFingerprint
                                    break
                                }
                                writer.println("REJECTED")
                                listener.onStatus("Prompt pairing not confirmed by client ($remoteAddress)")
                                return
                            }
                        }
                    }
                    if (!paired && promptFingerprint == null) {
                        writer.println("REJECTED")
                        listener.onStatus("Pairing failed for $remoteAddress ($clientFingerprint)")
                        return
                    }
                    if (promptFingerprint != null) {
                        pairingManager.completePromptPairing(promptFingerprint)
                        writer.println("PAIRED")
                        listener.onStatus("Prompt-paired new device $remoteAddress (${promptFingerprint.take(12)}…)")
                    } else {
                        pairingManager.addPairedDevice(clientFingerprint, remoteAddress)
                        writer.println("PAIRED")
                        listener.onStatus("Paired new device $remoteAddress (${clientFingerprint.take(12)}…)")
                    }
                } else {
                    writer.println("SECURE ${CryptoEngine.encodePublicKey(pairingManager.serverKeyPair.public)} ${CryptoEngine.encodeBytes(serverNonce)}")
                }

                val sessionKey = deriveSessionKey(clientPublicKey, clientNonce, serverNonce)
                val activeSession = sessionManager.createSession(
                    remoteAddress = remoteAddress,
                    fingerprint = clientFingerprint,
                    secured = true
                )
                session = activeSession
                listener.onSessionEstablished(activeSession)

                val channel = SecureFrameChannel(
                    DataInputStream(socket.getInputStream().buffered()),
                    DataOutputStream(socket.getOutputStream().buffered()),
                    sessionKey
                )

                val sink = SessionSink(activeSession.id, channel)
                StreamingBus.register(sink)
                try {
                    socket.soTimeout = IDLE_TIMEOUT_MS
                    serveSession(activeSession, channel)
                } finally {
                    StreamingBus.unregister(sink)
                    sink.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Session ended with ${session?.remoteAddress ?: "unknown"}: ${e.message}")
        } finally {
            session?.let {
                sessionManager.clearActiveSession(it)
                listener.onSessionEnded(it)
            }
        }
    }

    private fun deriveSessionKey(clientPublicKey: java.security.PublicKey, clientNonce: ByteArray, serverNonce: ByteArray): SecretKey =
        CryptoEngine.deriveSessionKey(
            privateKey = pairingManager.serverKeyPair.private,
            peerPublicKey = clientPublicKey,
            clientNonce = clientNonce,
            serverNonce = serverNonce
        )

    private fun serveSession(session: Session, channel: SecureFrameChannel) {
        while (true) {
            val plaintext = channel.receive() ?: break
            val envelope = Envelope.decode(plaintext)
            if (envelope == null) {
                channel.sendText("ERR malformed envelope")
                continue
            }
            when (envelope.channel) {
                Envelope.CHANNEL_CONTROL -> handleControl(session, channel, envelope.payload.toString(Charsets.UTF_8))
                else -> Log.d(TAG, "Ignoring unexpected upstream channel ${envelope.channel}")
            }
        }
    }

    private fun handleControl(session: Session, channel: SecureFrameChannel, command: String) {
        when {
            command == KEEPALIVE_PING -> channel.send(Envelope.encode(Envelope.CHANNEL_CONTROL, 0L, KEEPALIVE_PONG.toByteArray()))
            else -> {
                val accepted = listener.onCommandReceived(session, command)
                if (!accepted) {
                    channel.send(Envelope.encode(Envelope.CHANNEL_CONTROL, 0L, "ERR rejected: $command".toByteArray()))
                }
            }
        }
    }

    /**
     * Bridges the [StreamingBus] to a session's encrypted channel.
     *
     * Frames are queued and flushed by a dedicated writer thread so a slow
     * client never blocks the capture pipeline. When the queue saturates,
     * non-keyframe video frames are dropped first (control, audio, telemetry
     * and H.264 keyframes are never dropped).
     */
    private class SessionSink(
        override val sessionId: Long,
        private val channel: SecureFrameChannel
    ) : StreamingBus.Sink {

        private val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)

        @Volatile
        private var framesDropped = 0

        @Volatile
        private var open = true

        private val writerThread = Thread({
            try {
                while (open || queue.isNotEmpty()) {
                    val frame = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    channel.send(frame)
                }
            } catch (_: InterruptedException) {
                // Shutting down.
            } catch (e: Exception) {
                Log.w(TAG, "Sink writer failed for session $sessionId: ${e.message}")
            }
        }, "sndcpy-sink-$sessionId").apply {
            isDaemon = true
            start()
        }

        override fun onMediaFrame(channel: Int, ptsUs: Long, payload: ByteArray, keyframe: Boolean) {
            if (!open) return
            val envelope = Envelope.encode(channel, ptsUs, payload)

            val droppable = channel == Envelope.CHANNEL_VIDEO_H264 && !keyframe
            if (droppable && queue.remainingCapacity() == 0) {
                framesDropped++
                return
            }
            if (!queue.offer(envelope)) {
                framesDropped++
            }
        }

        fun close() {
            open = false
            writerThread.interrupt()
            if (framesDropped > 0) {
                Log.i(TAG, "Session $sessionId ended; dropped $framesDropped congested frames")
            }
        }

        companion object {
            const val QUEUE_CAPACITY = 240 // ~8s of video at 30fps
        }
    }

    companion object {
        private const val TAG = "TcpControlServer"
        const val PROTOCOL_NAME = "SNDCPY"
        const val LEGACY_HANDSHAKE_HELLO = "HELLO SNDCPY"
        private const val ACCEPT_TIMEOUT_MS = 1000
        private const val HANDSHAKE_TIMEOUT_MS = 8000
        private const val IDLE_TIMEOUT_MS = 30000
        private const val PAIRING_ATTEMPTS = 3
        private val WHITESPACE_REGEX = "\\s+".toRegex()
        private const val KEEPALIVE_PING = "PING"
        private const val KEEPALIVE_PONG = "PONG"
    }
}
