package com.SNDCPY.server.input

/**
 * Validates and executes remote input commands.
 *
 * Execution requires the accessibility input service
 * (`SndcpyAccessibilityService`) to be enabled by the user; commands are
 * rejected with a clear reason while it is unavailable.
 */
class BasicInputInjector {

    fun inject(command: String): Boolean {
        val parsed = InputCommand.parse(command) ?: return false
        val service = SndcpyAccessibilityService.INSTANCE ?: return false
        return service.execute(parsed)
    }

    fun canInject(): Boolean = SndcpyAccessibilityService.isAvailable

    /** Structured variant used for richer diagnostics. */
    fun inject(command: InputCommand): Boolean {
        val service = SndcpyAccessibilityService.INSTANCE ?: return false
        return service.execute(command)
    }
}
