package com.SNDCPY.server.streaming

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import com.SNDCPY.server.protocol.Envelope
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the device screen through a MediaProjection virtual display,
 * encodes it to H.264 with [MediaCodec] and publishes every access unit
 * on the [StreamingBus] as a VIDEO_H264 envelope.
 *
 * Pipeline: Surface -> VirtualDisplay -> MediaCodec(H.264) -> StreamingBus
 */
class ScreenCaptureStreamer(
    private val projection: MediaProjection,
    private val displayWidthPixels: Int,
    private val displayHeightPixels: Int,
    private val displayDensityDpi: Int,
    private val config: Config = Config()
) {

    data class Config(
        val maxWidth: Int = 1280,
        val maxHeight: Int = 720,
        val bitrateBitsPerSecond: Int = 4_000_000,
        val frameRate: Int = 30,
        val keyframeIntervalSeconds: Int = 1
    )

    private val running = AtomicBoolean(false)

    private var codec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null

    private val formatQueue = LinkedBlockingQueue<MediaFormat>(2)

    @Volatile
    private var lastPtsUs: Long = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return

        try {
            val (width, height) = scaledSize(displayWidthPixels, displayHeightPixels)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBitsPerSecond)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSeconds)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()

            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        val outputBuffer = codec.getOutputBuffer(index) ?: return
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // SPS/PPS configuration data — publish as a keyframe at pts 0.
                            val csd = ByteArray(info.size)
                            outputBuffer.position(info.offset)
                            outputBuffer.get(csd)
                            publish(Envelope.CHANNEL_VIDEO_H264, 0L, csd, keyframe = true)
                        } else if (info.size > 0) {
                            val frame = ByteArray(info.size)
                            outputBuffer.position(info.offset)
                            outputBuffer.get(frame)
                            val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            publish(Envelope.CHANNEL_VIDEO_H264, info.presentationTimeUs, frame, keyframe)
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    formatQueue.offer(format)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Encoder error", e)
                }
            }, null)

            encoder.start()
            codec = encoder

            virtualDisplay = projection.createVirtualDisplay(
                "SNDCPY-Capture",
                width, height, displayDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            drainThread = Thread({ drainFormatChanges() }, "sndcpy-video-drain").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "Screen capture streaming started at ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            stop()
            throw e
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            drainThread?.interrupt()
            drainThread = null
            virtualDisplay?.release()
            virtualDisplay = null
            codec?.let { c ->
                c.signalEndOfInputStream()
                c.stop()
                c.release()
            }
            codec = null
            Log.i(TAG, "Screen capture streaming stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error during stop: ${e.message}")
        }
    }

    fun isRunning(): Boolean = running.get()

    private fun publish(channel: Int, ptsUs: Long, payload: ByteArray, keyframe: Boolean) {
        lastPtsUs = ptsUs
        StreamingBus.broadcast(channel, ptsUs, payload, keyframe)
    }

    private fun drainFormatChanges() {
        while (running.get()) {
            val changed = try {
                formatQueue.poll(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            }
            // Format changes are already surfaced via CODEC_CONFIG frames; kept for future stats.
            changed?.let { Log.i(TAG, "Encoder output format changed: $it") }
        }
    }

    /** Scales down to fit within the configured max bounds, keeping even dimensions. */
    private fun scaledSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val aspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        var width = sourceWidth
        var height = sourceHeight
        if (width > config.maxWidth) {
            width = config.maxWidth
            height = (width / aspect).toInt()
        }
        if (height > config.maxHeight) {
            height = config.maxHeight
            width = (height * aspect).toInt()
        }
        return (width / 2 * 2).coerceAtLeast(2) to (height / 2 * 2).coerceAtLeast(2)
    }

    companion object {
        private const val TAG = "ScreenCaptureStreamer"
    }
}
