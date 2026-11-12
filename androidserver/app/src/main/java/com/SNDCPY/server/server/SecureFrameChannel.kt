package com.SNDCPY.server.server

import com.SNDCPY.server.security.CryptoEngine
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import javax.crypto.SecretKey

/**
 * Length-prefixed AES-GCM encrypted message channel.
 *
 * Wire format per frame: [4-byte big-endian length][12-byte IV][ciphertext+tag]
 */
class SecureFrameChannel(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sessionKey: SecretKey
) {

    /** Sends one encrypted frame. Thread-safe against concurrent writers. */
    fun send(plaintext: ByteArray) {
        val frameBody = CryptoEngine.encrypt(sessionKey, plaintext)
        synchronized(output) {
            output.writeInt(frameBody.size)
            output.write(frameBody)
            output.flush()
        }
    }

    fun sendText(text: String) = send(text.toByteArray(Charsets.UTF_8))

    /**
     * Blocks until a full frame is decrypted, or returns null on clean EOF.
     * Throws [IOException] on truncation or authentication failure.
     */
    fun receive(): ByteArray? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw IOException("Invalid frame length $length")
        }
        val frameBody = ByteArray(length)
        input.readFully(frameBody)
        return CryptoEngine.decrypt(sessionKey, frameBody)
    }

    fun receiveText(): String? = receive()?.toString(Charsets.UTF_8)

    companion object {
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }
}
