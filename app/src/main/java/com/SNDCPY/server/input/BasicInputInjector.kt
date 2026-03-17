package com.example.remotecontrolserver.input

class BasicInputInjector {
    fun inject(command: String): Boolean {
        val normalized = command.trim().uppercase()
        return normalized == "TAP" ||
            normalized == "SWIPE" ||
            normalized == "SCROLL" ||
            normalized == "HOME" ||
            normalized == "BACK" ||
            normalized.startsWith("KEY:")
    }
}
