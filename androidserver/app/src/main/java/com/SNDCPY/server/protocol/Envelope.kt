package com.SNDCPY.server.protocol

import java.nio.ByteBuffer

/**
 * Binary message envelope multiplexed inside every encrypted frame
 * once a session is established (wire protocol v2).
 *
 * Layout: [1-byte channel][8-byte big-endian presentation timestamp µs][payload]
 */
object Envelope {

    const val CHANNEL_CONTROL: Int = 0x01
    const val CHANNEL_VIDEO_H264: Int = 0x02
    const val CHANNEL_AUDIO_AAC: Int = 0x03
    const val CHANNEL_TELEMETRY: Int = 0x04

    const val HEADER_BYTES: Int = 9

    /** Wire protocol version advertised via NSD TXT records and telemetry. */
    const val PROTOCOL_VERSION: String = "2"

    fun encode(channel: Int, ptsUs: Long, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + payload.size)
        buffer.put(channel.toByte())
        buffer.putLong(ptsUs)
        buffer.put(payload)
        return buffer.array()
    }

    /** Returns null when the plaintext is not a valid envelope. */
    fun decode(bytes: ByteArray): DecodedEnvelope? {
        if (bytes.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes)
        val channel = buffer.get().toInt() and 0xFF
        val ptsUs = buffer.long
        val payload = ByteArray(bytes.size - HEADER_BYTES)
        buffer.get(payload)
        return DecodedEnvelope(channel, ptsUs, payload)
    }

    data class DecodedEnvelope(val channel: Int, val ptsUs: Long, val payload: ByteArray)
}
