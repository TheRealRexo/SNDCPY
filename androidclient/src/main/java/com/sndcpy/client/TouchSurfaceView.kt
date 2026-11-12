package com.sndcpy.client

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceView

/**
 * Overlay that mirrors the server's touch panel. Every touch pointer is
 * tracked and reported to the server as a sequence of POINTER_DOWN / MOVE / UP
 * commands, so genuine multi-touch (pinch-to-zoom, rotate) is transmitted.
 *
 * Coordinates are mapped from the view's pixel space to the server's screen
 * space. When [serverWidth] / [serverHeight] are set (via the video format),
 * the mapping is proportional; otherwise the view size is used.
 */
class TouchSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {

    interface Listener {
        fun onPointerDown(pointerId: Int, x: Float, y: Float)
        fun onPointerMove(pointerId: Int, x: Float, y: Float)
        fun onPointerUp(pointerId: Int)
    }

    var listener: Listener? = null

    @Volatile
    var serverWidth = 1280
        set(value) { field = if (value > 0) value else 1280 }
    @Volatile
    var serverHeight = 720
        set(value) { field = if (value > 0) value else 720 }

    // Map of android pointerId (raw index from event) → server pointer counter.
    private val activePointers = HashMap<Int, Int>()
    private var nextServerPointer = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val l = listener ?: return true

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerIdOnly = event.getPointerId(index)
                val serverId = nextServerPointer++
                activePointers[pointerIdOnly] = serverId
                val (sx, sy) = mapToServer(event.getX(index), event.getY(index))
                l.onPointerDown(serverId, sx, sy)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val serverId = activePointers[pointerId] ?: continue
                    val (sx, sy) = mapToServer(event.getX(i), event.getY(i))
                    l.onPointerMove(serverId, sx, sy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val serverId = activePointers.remove(pointerId)
                if (serverId != null) l.onPointerUp(serverId)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (serverId in activePointers.values) l.onPointerUp(serverId)
                activePointers.clear()
            }
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun mapToServer(x: Float, y: Float): Pair<Float, Float> {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return x to y
        return (x / vw * serverWidth) to (y / vh * serverHeight)
    }
}
