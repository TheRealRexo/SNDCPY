package com.sndcpy.client

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/**
 * Decodes the H.264 Annex-B NAL stream received from the SNDCPY server and
 * renders it to a [Surface].
 *
 * Each SNDCPY video envelope payload is a raw H.264 access unit (one or more
 * NALs beginning with 00 00 00 01). Because the SPS/PPS commonly arrive
 * in-band ahead of each IDR frame, [MediaCodec] self-configures; we simply
 * queue each access unit with its presentation timestamp.
 *
 * The decoder must be created on a thread with a Looper (the main thread).
 */
class VideoDecoderRenderer(private val surface: Surface, private val width: Int, private val height: Int) {

    private var codec: MediaCodec? = null
    private var started = false

    @Volatile
    var videoWidth = width
    @Volatile
    var videoHeight = height

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, surface, null, 0)
                start()
            }
            started = true
        } catch (_: Exception) {
            try { codec?.release() } catch (_: Exception) {}
            codec = null
        }
    }

    fun release() {
        try { if (started) codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    fun isReady(): Boolean = codec != null

    /** Feeds one H.264 access unit to the decoder and drains rendered frames. */
    fun feed(payload: ByteArray, ptsUs: Long) {
        val codec = codec ?: return
        try {
            drainDecodedFrames(codec)

            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) return
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(payload)
            codec.queueInputBuffer(inputIndex, 0, payload.size, ptsUs, 0)
        } catch (_: Exception) {}
    }

    private fun drainDecodedFrames(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = codec.outputFormat
                if (fmt.containsKey(MediaFormat.KEY_WIDTH)) videoWidth = fmt.getInteger(MediaFormat.KEY_WIDTH)
                if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) videoHeight = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            } else if (outIndex >= 0) {
                codec.releaseOutputBuffer(outIndex, if (info.size > 0) true else false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            } else {
                break
            }
        }
    }

    companion object {
        private const val TIMEOUT_US = 0L
    }
}
