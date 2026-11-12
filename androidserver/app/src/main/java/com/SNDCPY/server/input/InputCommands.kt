package com.SNDCPY.server.input

/**
 * Parsed remote input commands (wire grammar v1).
 *
 *   TAP <x> <y>
 *   LONG_PRESS <x> <y>
 *   SWIPE <x1> <y1> <x2> <y2> [durationMs]
 *   SCROLL <UP|DOWN|LEFT|RIGHT> [<x> <y>]
 *   HOME | BACK | RECENTS
 *   KEY:<android-keycode>   (mapped to global actions where possible)
 */
sealed interface InputCommand {

    data class Tap(val x: Float, val y: Float) : InputCommand

    data class LongPress(val x: Float, val y: Float) : InputCommand

    data class Swipe(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long = DEFAULT_DURATION_MS
    ) : InputCommand {
        companion object {
            const val DEFAULT_DURATION_MS = 200L
            const val MAX_DURATION_MS = 5_000L
        }
    }

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    data class Scroll(
        val direction: Direction,
        val centerX: Float? = null,
        val centerY: Float? = null
    ) : InputCommand

    data object Home : InputCommand

    data object Back : InputCommand

    data object Recents : InputCommand

    data class Key(val keyCode: Int) : InputCommand

    companion object {

        private val WHITESPACE_REGEX = "\\s+".toRegex()
        private const val KEY_PREFIX = "KEY:"

        /** Parses a command string; returns null when malformed or unknown. */
        fun parse(raw: String): InputCommand? {
            val trimmed = raw.trim().uppercase()
            if (trimmed.startsWith(KEY_PREFIX)) {
                return trimmed.removePrefix(KEY_PREFIX).toIntOrNull()?.let { Key(it) }
            }

            val tokens = trimmed.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null

            return when (tokens[0]) {
                "TAP" -> point(tokens, 1)?.let { Tap(it.first, it.second) }
                "LONG_PRESS" -> point(tokens, 1)?.let { LongPress(it.first, it.second) }
                "SWIPE" -> swipe(tokens)
                "SCROLL" -> scroll(tokens)
                "HOME" -> Home.takeIf { tokens.size == 1 }
                "BACK" -> Back.takeIf { tokens.size == 1 }
                "RECENTS" -> Recents.takeIf { tokens.size == 1 }
                else -> null
            }
        }

        private fun point(tokens: List<String>, offset: Int): Pair<Float, Float>? {
            if (tokens.size < offset + 2) return null
            val x = tokens[offset].toFloatOrNull() ?: return null
            val y = tokens[offset + 1].toFloatOrNull() ?: return null
            return x to y
        }

        private fun swipe(tokens: List<String>): Swipe? {
            if (tokens.size !in 6..7) return null
            val x1 = tokens[1].toFloatOrNull() ?: return null
            val y1 = tokens[2].toFloatOrNull() ?: return null
            val x2 = tokens[3].toFloatOrNull() ?: return null
            val y2 = tokens[4].toFloatOrNull() ?: return null
            val duration = when (tokens.size) {
                7 -> tokens[6].toLongOrNull()?.takeIf { it in 10..Swipe.MAX_DURATION_MS } ?: return null
                else -> Swipe.DEFAULT_DURATION_MS
            }
            // tokens[5] is reserved for an optional gesture id and ignored.
            return Swipe(x1, y1, x2, y2, duration)
        }

        private fun scroll(tokens: List<String>): Scroll? {
            val direction = try {
                Direction.valueOf(tokens.getOrNull(1) ?: return null)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (tokens.size == 2) return Scroll(direction)
            if (tokens.size == 4) {
                val x = tokens[2].toFloatOrNull() ?: return null
                val y = tokens[3].toFloatOrNull() ?: return null
                return Scroll(direction, x, y)
            }
            return null
        }
    }
}
