package com.SNDCPY.server.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SessionManager {
    private val idGenerator = AtomicLong(1)
    private val activeSessions = ConcurrentHashMap<Long, Session>()

    fun createSession(remoteAddress: String, fingerprint: String? = null, secured: Boolean = false): Session {
        val session = Session(
            id = idGenerator.getAndIncrement(),
            remoteAddress = remoteAddress,
            fingerprint = fingerprint,
            secured = secured
        )
        activeSessions[session.id] = session
        return session
    }

    fun getActiveSession(): Session? = activeSessions.values.firstOrNull()

    fun getSessions(): List<Session> = activeSessions.values.sortedBy { it.id }

    fun activeSessionCount(): Int = activeSessions.size

    fun clearActiveSession(session: Session) {
        activeSessions.remove(session.id)
    }
}
