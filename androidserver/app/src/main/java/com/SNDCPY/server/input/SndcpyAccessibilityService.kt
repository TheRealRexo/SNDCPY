package com.SNDCPY.server.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Executes remote input commands as accessibility gestures and global actions.
 *
 * Requires the user to enable the service in system settings; the app opens
 * `Settings.ACTION_ACCESSIBILITY_SETTINGS` for one-tap enablement.
 */
class SndcpyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        INSTANCE = this
        Log.i(TAG, "Accessibility input service connected")
        onAvailabilityChanged?.invoke(true)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility input service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "Accessibility input service unbound")
        INSTANCE = null
        onAvailabilityChanged?.invoke(false)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onDestroy() {
        INSTANCE = null
        super.onDestroy()
    }

    fun execute(command: InputCommand): Boolean {
        when (command) {
            is InputCommand.Tap -> dispatchStroke(command.x, command.y, command.x, command.y, TAP_DURATION_MS)

            is InputCommand.LongPress -> dispatchStroke(command.x, command.y, command.x, command.y, LONG_PRESS_DURATION_MS)

            is InputCommand.Swipe -> dispatchStroke(
                command.startX, command.startY,
                command.endX, command.endY,
                command.durationMs
            )

            is InputCommand.Scroll -> {
                val metrics = resources.displayMetrics
                val cx = command.centerX ?: metrics.widthPixels / 2f
                val cy = command.centerY ?: metrics.heightPixels / 2f
                val distance = minOf(metrics.widthPixels, metrics.heightPixels) * SCROLL_DISTANCE_RATIO
                val (startX, startY, endX, endY) = scrollPoints(command.direction, cx, cy, distance)
                dispatchStroke(startX, startY, endX, endY, SCROLL_DURATION_MS)
            }

            InputCommand.Home -> performGlobalAction(GLOBAL_ACTION_HOME)

            InputCommand.Back -> performGlobalAction(GLOBAL_ACTION_BACK)

            InputCommand.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)

            is InputCommand.Key -> return executeKey(command.keyCode)
        }
        return true
    }

    /** Only keycodes expressible as global actions can be injected. */
    private fun executeKey(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { performGlobalAction(GLOBAL_ACTION_BACK); true }
            KeyEvent.KEYCODE_HOME -> { performGlobalAction(GLOBAL_ACTION_HOME); true }
            KeyEvent.KEYCODE_APP_SWITCH -> { performGlobalAction(GLOBAL_ACTION_RECENTS); true }
            else -> false
        }

    private fun scrollPoints(
        direction: InputCommand.Direction,
        cx: Float,
        cy: Float,
        distance: Float
    ): FloatArray = when (direction) {
        // Finger travels opposite to content movement.
        InputCommand.Direction.UP -> floatArrayOf(cx, cy + distance, cx, cy - distance)
        InputCommand.Direction.DOWN -> floatArrayOf(cx, cy - distance, cx, cy + distance)
        InputCommand.Direction.LEFT -> floatArrayOf(cx + distance, cy, cx - distance, cy)
        InputCommand.Direction.RIGHT -> floatArrayOf(cx - distance, cy, cx + distance, cy)
    }

    private fun dispatchStroke(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "SndcpyA11yService"

        @Volatile
        var INSTANCE: SndcpyAccessibilityService? = null
            private set

        /** Optional hook so the runtime can surface availability changes in the UI. */
        @Volatile
        var onAvailabilityChanged: ((Boolean) -> Unit)? = null

        val isAvailable: Boolean
            get() = INSTANCE != null

        private const val TAP_DURATION_MS = 50L
        private const val LONG_PRESS_DURATION_MS = 600L
        private const val SCROLL_DURATION_MS = 300L
        private const val SCROLL_DISTANCE_RATIO = 0.25f
    }
}
