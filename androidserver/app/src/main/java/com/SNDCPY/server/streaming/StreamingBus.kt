package com.SNDCPY.server.streaming

import android.util.Log
import com.SNDCPY.server.protocol.Envelope
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Fan-out hub that pushes encoded media frames to every live session.
 * Sinks are expected to be cheap to notify; slow sinks are skipped rather
 * than allowed to stall the capture pipeline (full back-pressure lands in 1.0).
 */
object StreamingBus {

    private const val TAG = "StreamingBus"

    interface Sink {
        fun onMediaFrame(channel: Int, ptsUs: Long, payload: ByteArray, keyframe: Boolean)
        val sessionId: Long
    }

    private val sinks = CopyOnWriteArraySet<Sink>()
    private val running = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)

    fun register(sink: Sink) {
        sinks.add(sink)
        Log.i(TAG, "Sink registered (session ${sink.sessionId}), total=${sinks.size}")
    }

    fun unregister(sink: Sink) {
        sinks.remove(sink)
        Log.i(TAG, "Sink removed (session ${sink.sessionId}), total=${sinks.size}")
    }

    fun broadcast(channel: Int, ptsUs: Long, payload: ByteArray, keyframe: Boolean = false) {
        if (!running.get()) return
        frameCounter.incrementAndGet()
        sinks.forEach { sink ->
            try {
                sink.onMediaFrame(channel, ptsUs, payload, keyframe)
            } catch (e: Exception) {
                Log.w(TAG, "Dropping frame for session ${sink.sessionId}: ${e.message}")
            }
        }
    }

    fun start() {
        running.set(true)
    }

    fun stop() {
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()

    fun framesSent(): Long = frameCounter.get()

    fun subscriberCount(): Int = sinks.size

    /** Convenience helper so sinks can build envelopes consistently. */
    fun envelopeFor(channel: Int, ptsUs: Long, payload: ByteArray): ByteArray =
        Envelope.encode(channel, ptsUs, payload)
}
