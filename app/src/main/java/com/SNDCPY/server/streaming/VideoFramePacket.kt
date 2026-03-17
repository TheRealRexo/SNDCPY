package com.example.remotecontrolserver.streaming

data class VideoFramePacket(
    val frameIndex: Long,
    val timestampMs: Long = System.currentTimeMillis()
)
