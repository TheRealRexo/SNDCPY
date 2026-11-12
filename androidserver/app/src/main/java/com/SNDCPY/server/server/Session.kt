package com.SNDCPY.server.server

import java.util.concurrent.atomic.AtomicLong

data class Session(
    val id: Long,
    val remoteAddress: String,
    val fingerprint: String? = null,
    val secured: Boolean = false,
    val connectedAtEpochMs: Long = System.currentTimeMillis()
)
