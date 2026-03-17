package com.example.remotecontrolserver.server

import java.util.concurrent.atomic.AtomicLong

class SessionManager {
    private val idGenerator = AtomicLong(1)

    @Volatile
    private var activeSession: Session? = null

    fun createSession(remoteAddress: String): Session {
        val session = Session(id = idGenerator.getAndIncrement(), remoteAddress = remoteAddress)
        activeSession = session
        return session
    }

    fun getActiveSession(): Session? = activeSession

    fun clearActiveSession() {
        activeSession = null
    }
}
