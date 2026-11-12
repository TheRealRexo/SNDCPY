package com.SNDCPY.server.runtime

import com.SNDCPY.server.streaming.ScreenCaptureStreamer

/**
 * Mutable server configuration. Clients may tune these at runtime via
 * control-channel commands; changes apply on the next capture start.
 */
object RuntimeConfig {

    @Volatile
    var videoBitrateBitsPerSecond: Int = 4_000_000
        set(value) {
            field = value.coerceIn(MIN_BITRATE, MAX_BITRATE)
        }

    @Volatile
    var frameRate: Int = 30
        set(value) {
            field = value.coerceIn(MIN_FPS, MAX_FPS)
        }

    @Volatile
    var maxWidth: Int = 1280

    @Volatile
    var maxHeight: Int = 720

    fun captureConfig(): ScreenCaptureStreamer.Config = ScreenCaptureStreamer.Config(
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        bitrateBitsPerSecond = videoBitrateBitsPerSecond,
        frameRate = frameRate
    )

    const val MIN_BITRATE = 500_000
    const val MAX_BITRATE = 20_000_000
    const val MIN_FPS = 10
    const val MAX_FPS = 60
}
