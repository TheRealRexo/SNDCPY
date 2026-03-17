package com.example.remotecontrolserver.streaming

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class VideoStreamingPrototype {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val frameCounter = AtomicLong(0)
    private var ticker: ScheduledFuture<*>? = null

    fun start(onFrame: (VideoFramePacket) -> Unit) {
        if (ticker?.isCancelled == false || ticker?.isDone == false) return

        ticker = scheduler.scheduleAtFixedRate({
            val frame = VideoFramePacket(frameCounter.incrementAndGet())
            onFrame(frame)
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        ticker?.cancel(true)
        ticker = null
    }
}
