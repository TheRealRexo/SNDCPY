package com.SNDCPY.server.telemetry

import com.SNDCPY.server.protocol.Envelope
import com.SNDCPY.server.streaming.StreamingBus
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Periodically samples server health and broadcasts a JSON snapshot on the
 * telemetry channel, while also surfacing a compact line to the status UI.
 */
class TelemetryCollector(
    private val sessionCountProvider: () -> Int,
    private val onTelemetryLine: (String) -> Unit
) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sndcpy-telemetry").apply { isDaemon = true }
    }

    private var task: ScheduledFuture<*>? = null

    private val lastFrameCount = AtomicLong(0)
    private val lastSampleAtMs = AtomicLong(System.currentTimeMillis())
    private val fpsEstimate = AtomicReference(0.0)

    fun start(periodSeconds: Long = DEFAULT_PERIOD_SECONDS) {
        if (task?.isCancelled == false) return
        task = scheduler.scheduleAtFixedRate({
            try {
                publishSnapshot()
            } catch (_: Exception) {
                // Telemetry must never take the server down.
            }
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS)
    }

    fun stop() {
        task?.cancel(false)
        task = null
    }

    fun snapshot(): JSONObject {
        val now = System.currentTimeMillis()
        val frames = StreamingBus.framesSent()

        val previousFrames = lastFrameCount.getAndSet(frames)
        val previousTime = lastSampleAtMs.getAndSet(now)
        val elapsedSeconds = ((now - previousTime).coerceAtLeast(1)) / 1000.0
        val instantFps = (frames - previousFrames).coerceAtLeast(0) / elapsedSeconds

        // Exponential smoothing so the reported number is stable.
        val smoothed = 0.7 * fpsEstimate.get() + 0.3 * instantFps
        fpsEstimate.set(smoothed)

        val runtime = Runtime.getRuntime()
        return JSONObject().apply {
            put("proto", Envelope.PROTOCOL_VERSION)
            put("uptime_ms", now - startedAtMs)
            put("sessions", sessionCountProvider())
            put("sinks", StreamingBus.subscriberCount())
            put("frames_sent", frames)
            put("fps", Math.round(smoothed * 10.0) / 10.0)
            put("streaming", StreamingBus.isRunning())
            put("heap_used_mb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
            put("heap_max_mb", runtime.maxMemory() / (1024 * 1024))
        }
    }

    private fun publishSnapshot() {
        val json = snapshot()
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        StreamingBus.broadcast(Envelope.CHANNEL_TELEMETRY, System.currentTimeMillis() * 1000, payload)
        onTelemetryLine("telemetry: $json")
    }

    companion object {
        private const val TAG = "TelemetryCollector"
        const val DEFAULT_PERIOD_SECONDS = 5L
        private val startedAtMs = System.currentTimeMillis()
    }
}
