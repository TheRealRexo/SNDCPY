package com.SNDCPY.server.streaming

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.SNDCPY.server.protocol.Envelope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures device audio playback through a MediaProjection playback-capture
 * session and publishes AAC-encoded access units on the [StreamingBus].
 *
 * Pipeline: AudioPlaybackCapture -> AudioRecord -> MediaCodec(AAC-LC) -> StreamingBus
 *
 * Requires Android 10 (API 29); unavailable on older releases by platform law.
 */
class AudioStreamer(
    private val projection: MediaProjection,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val channelCount: Int = DEFAULT_CHANNEL_COUNT,
    private val bitrateBitsPerSecond: Int = DEFAULT_BITRATE
) {

    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var workerThread: Thread? = null

    @Volatile
    private var totalFramesRead: Long = 0L

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Audio playback capture requires Android 10+; skipping")
            return
        }
        if (!running.compareAndSet(false, true)) return

        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val channelMask = if (channelCount == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }
            val pcmFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val minBufferBytes = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(pcmFormat)
                .setBufferSizeInBytes(maxOf(minBufferBytes * 2, 16384))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBitsPerSecond)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(minBufferBytes * 2, 16384))
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codec = encoder

            audioRecord?.startRecording()

            workerThread = Thread({ encodeLoop() }, "sndcpy-audio").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Audio streaming started (${sampleRate}Hz, ${channelCount}ch, ${bitrateBitsPerSecond}bps)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio streaming", e)
            stop()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            workerThread?.join(1000)
            workerThread = null
            try {
                audioRecord?.stop()
            } catch (_: IllegalStateException) {
                // Already stopped.
            }
            audioRecord?.release()
            audioRecord = null
            codec?.stop()
            codec?.release()
            codec = null
            Log.i(TAG, "Audio streaming stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error during audio stop: ${e.message}")
        }
    }

    fun isRunning(): Boolean = running.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun encodeLoop() {
        val record = audioRecord ?: return
        val encoder = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmChunk = ByteArray(PCM_CHUNK_BYTES)

        while (running.get()) {
            // Feed one chunk of captured PCM.
            val inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex)!!
                inputBuffer.clear()
                val bytesRead = record.read(pcmChunk, 0, minOf(pcmChunk.size, inputBuffer.capacity()))
                if (bytesRead > 0) {
                    val ptsUs = totalFramesRead * 1_000_000L / sampleRate
                    totalFramesRead += bytesRead / BYTES_PER_FRAME_UNIT
                    inputBuffer.put(pcmChunk, 0, bytesRead)
                    encoder.queueInputBuffer(inputIndex, 0, bytesRead, ptsUs, 0)
                } else {
                    encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    sleepBriefly()
                }
            }

            // Drain encoded AAC.
            var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // AAC csd-0 configuration — publish first so decoders can init.
                        val csdBuffer = encoder.outputFormat.getByteBuffer("csd-0")
                        val csd = ByteArray(csdBuffer?.remaining() ?: 0)
                        csdBuffer?.get(csd)
                        StreamingBus.broadcast(Envelope.CHANNEL_AUDIO_AAC, 0L, csd, keyframe = true)
                        Log.i(TAG, "AAC format published")
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val frame = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(frame)
                            StreamingBus.broadcast(
                                Envelope.CHANNEL_AUDIO_AAC,
                                bufferInfo.presentationTimeUs,
                                frame,
                                keyframe = false
                            )
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
                outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            }
        }
    }

    private fun sleepBriefly() {
        try {
            Thread.sleep(POLL_SLEEP_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "AudioStreamer"
        const val DEFAULT_SAMPLE_RATE = 44_100
        const val DEFAULT_CHANNEL_COUNT = 2
        const val DEFAULT_BITRATE = 128_000
        private const val PCM_CHUNK_BYTES = 8192
        private const val INPUT_TIMEOUT_US = 20_000L
        private const val POLL_SLEEP_MS = 10L
        private const val BYTES_PER_FRAME_UNIT = 2 // 16-bit samples; per-frame accounting is approximate
    }
}
