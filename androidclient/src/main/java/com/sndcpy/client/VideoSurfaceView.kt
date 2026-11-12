package com.sndcpy.client

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * A [SurfaceView] that owns a [VideoDecoderRenderer]. The renderer is
 * created lazily once the surface is ready; video frames are then queued
 * via [feed].
 */
class VideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    var renderer: VideoDecoderRenderer? = null
        private set

    var onFrame: ((ByteArray, Long) -> Unit)? = null

    init {
        holder.addCallback(this)
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val w = width.takeIf { it > 0 } ?: 720
        val h = height.takeIf { it > 0 } ?: 1280
        renderer = VideoDecoderRenderer(holder.surface, w, h)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer?.release()
        renderer = null
    }

    fun feed(payload: ByteArray, ptsUs: Long) {
        renderer?.feed(payload, ptsUs)
    }

    fun setServerSize(w: Int, h: Int) {
        renderer?.let {
            it.videoWidth = if (w > 0) w else it.videoWidth
            it.videoHeight = if (h > 0) h else it.videoHeight
        }
    }
}