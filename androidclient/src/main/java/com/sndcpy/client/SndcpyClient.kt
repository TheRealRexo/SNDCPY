package com.sndcpy.client

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPair
import javax.crypto.SecretKey

/**
 * Manages the SNDCPY secure connection to the Android server.
 *
 * Pipeline: plain-text handshake (HELLO → SECURE/PAIR) then switch to
 * length-prefixed AES-256-GCM frames. Each decrypted plaintext is an
 * [Envelope] multiplexing control / video / audio / telemetry channels.
 *
 * Messages are delivered on [listener] from this instance's own worker thread.
 */
class SndcpyClient(
    private val host: String,
    private val port: Int = 27183
) {
    interface Listener {
        fun onStateChanged(state: State, detail: String? = null)
        fun onMessage(text: String)
        fun onFrameDecoded(envelope: Envelope)
    }

    enum class State { IDLE, CONNECTING, PAIRING, CONNECTED, VIDEO_READY, FAILED, DISCONNECTED }

    private val keyPair: KeyPair = ClientCrypto.generateKeyPair()
    private val nonce: ByteArray = ClientCrypto.randomNonce()

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var sessionKey: SecretKey? = null
    private var running = false
    private var thread: Thread? = null

    @Volatile
    var listener: Listener? = null

    /** Invoked (on the main thread) when a pairing code must be entered. */
    var onPairingRequired: (() -> Unit)? = null

    // Synchronisation for waiting on the user-entered pairing code.
    private val pairingLock = Object()
    private var pendingPairingCode: String? = null
    private var pairingAborted = false

    fun connect() {
        if (running) return
        running = true
        thread = Thread({ runSession() }, "sndcpy-client").apply {
            isDaemon = true
            start()
        }
    }

    /** Call from the UI thread after the user enters the 6-digit code. */
    fun submitPairingCode(code: String) {
        synchronized(pairingLock) {
            if (code.isBlank()) {
                pairingAborted = true
            } else {
                pendingPairingCode = code
            }
            pairingLock.notifyAll()
        }
    }

    fun disconnect() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        listener?.onStateChanged(State.DISCONNECTED)
    }

    /** Sends a control command on the encrypted channel. */
    fun sendControl(text: String) {
        val key = sessionKey ?: return
        val out = output ?: return
        val plaintext = Envelope.encode(Envelope.CHANNEL_CONTROL, System.currentTimeMillis() * 1000, text.toByteArray(Charsets.UTF_8))
        try {
            val frame = ClientCrypto.encrypt(key, plaintext)
            synchronized(out) {
                out.writeInt(frame.size)
                out.write(frame)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    private fun runSession() {
        try {
            listener?.onStateChanged(State.CONNECTING)
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.tcpNoDelay = true
            socket = sock
            input = DataInputStream(sock.getInputStream().buffered())
            output = DataOutputStream(sock.getOutputStream().buffered())

            val writer = java.io.PrintWriter(sock.getOutputStream(), true)
            val reader = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream()))

            // Handshake
            writer.println("HELLO SNDCPY ${ClientCrypto.encodePublicKey(keyPair.public)} ${ClientCrypto.encodeBytes(nonce)}")
            val response = reader.readLine() ?: throw IOException("No handshake response")

            val tokens = response.split(" ".toRegex()).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) throw IOException("Malformed handshake")

            when (tokens[0]) {
                "SECURE" -> {
                    val serverKey = ClientCrypto.decodePublicKey(tokens[1])
                    val serverNonce = ClientCrypto.decodeBytes(tokens[2])
                    sessionKey = ClientCrypto.deriveSessionKey(keyPair.private, serverKey, nonce, serverNonce)
                    listener?.onStateChanged(State.CONNECTED)
                }
                "PAIRING_REQUIRED" -> {
                    val serverKey = ClientCrypto.decodePublicKey(tokens[1])
                    val serverNonce = ClientCrypto.decodeBytes(tokens[2])
                    sessionKey = ClientCrypto.deriveSessionKey(keyPair.private, serverKey, nonce, serverNonce)
                    // Ask the UI to show the pairing dialog (main thread).
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onPairingRequired?.invoke()
                    }
                    val code = waitForPairingCode()
                    writer.println("PAIR $code")
                    val result = reader.readLine() ?: throw IOException("No pairing result")
                    if (result.trim() == "PAIRED") {
                        listener?.onStateChanged(State.CONNECTED)
                    } else {
                        listener?.onStateChanged(State.FAILED, "Pairing rejected by server")
                        return
                    }
                }
                "ERROR" -> {
                    listener?.onStateChanged(State.FAILED, response)
                    return
                }
                else -> {
                    listener?.onStateChanged(State.FAILED, "Unexpected handshake: $response")
                    return
                }
            }

            readLoop()
        } catch (e: Exception) {
            if (running) {
                listener?.onStateChanged(State.FAILED, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Reads encrypted frames, decrypts, parses envelopes, and dispatches. */
    private fun readLoop() {
        listener?.onStateChanged(State.VIDEO_READY)
        try {
            while (running) {
                val frame = readFrame() ?: break
                val plaintext = ClientCrypto.decrypt(sessionKey ?: break, frame)
                val envelope = Envelope.decode(plaintext)
                if (envelope != null) {
                    when (envelope.channel) {
                        Envelope.CHANNEL_CONTROL -> listener?.onMessage(String(envelope.payload, Charsets.UTF_8))
                        Envelope.CHANNEL_VIDEO_H264 -> listener?.onFrameDecoded(envelope)
                        // audio & telemetry ignored for the control-only client
                    }
                }
            }
        } catch (e: Exception) {
            if (running) listener?.onStateChanged(State.FAILED, e.message ?: "Connection error")
        } finally {
            running = false
        }
    }

    private fun readFrame(): ByteArray? {
        val input = input ?: return null
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length <= 0 || length > MAX_FRAME_BYTES) throw IOException("Invalid frame length $length")
        val frame = ByteArray(length)
        input.readFully(frame)
        return frame
    }

    /** Blocks the worker thread until the user enters a pairing code (or times out). */
    private fun waitForPairingCode(): String {
        synchronized(pairingLock) {
            val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
            while (pendingPairingCode == null && !pairingAborted) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                try { pairingLock.wait(remaining) } catch (_: InterruptedException) { break }
            }
            if (pairingAborted) throw IOException("Pairing cancelled")
            return pendingPairingCode ?: throw IOException("Pairing code entry timed out")
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val PAIRING_TIMEOUT_MS = 60000
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }
}

// Minimal envelope mirror (kept package-private to SNDCPY).
data class Envelope(val channel: Int, val ptsUs: Long, val payload: ByteArray) {
    object Channels {
        const val CONTROL = 0x01
        const val VIDEO_H264 = 0x02
        const val AUDIO_AAC = 0x03
        const val TELEMETRY = 0x04
    }

    companion object {
        const val HEADER_BYTES: Int = 9
        const val CHANNEL_CONTROL: Int = 0x01
        const val CHANNEL_VIDEO_H264: Int = 0x02
        const val CHANNEL_AUDIO_AAC: Int = 0x03
        const val CHANNEL_TELEMETRY: Int = 0x04

        fun encode(channel: Int, ptsUs: Long, payload: ByteArray): ByteArray {
            val buffer = ByteBuffer.allocate(HEADER_BYTES + payload.size)
            buffer.put(channel.toByte())
            buffer.putLong(ptsUs)
            buffer.put(payload)
            return buffer.array()
        }

        fun decode(bytes: ByteArray): Envelope? {
            if (bytes.size < HEADER_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes)
            val channel = buffer.get().toInt() and 0xFF
            val ptsUs = buffer.long
            val payload = ByteArray(bytes.size - HEADER_BYTES)
            buffer.get(payload)
            return Envelope(channel, ptsUs, payload)
        }
    }
}
