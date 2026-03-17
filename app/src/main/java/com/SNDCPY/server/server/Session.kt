package com.example.remotecontrolserver.server

data class Session(
    val id: Long,
    val remoteAddress: String,
    val connectedAtEpochMs: Long = System.currentTimeMillis()
)
