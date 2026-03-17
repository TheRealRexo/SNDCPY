package com.example.remotecontrolserver.runtime

import com.example.remotecontrolserver.discovery.LocalDiscoveryPrototype
import com.example.remotecontrolserver.input.BasicInputInjector
import com.example.remotecontrolserver.server.Session
import com.example.remotecontrolserver.server.SessionManager
import com.example.remotecontrolserver.server.TcpControlServer
import com.example.remotecontrolserver.streaming.VideoStreamingPrototype

class RemoteControlRuntime(
    private val onStatus: (String) -> Unit
) {
    private val sessionManager = SessionManager()
    private val discovery = LocalDiscoveryPrototype()
    private val inputInjector = BasicInputInjector()
    private val videoStreaming = VideoStreamingPrototype()

    private val server = TcpControlServer(
        port = PORT,
        sessionManager = sessionManager,
        onSessionEstablished = ::onSessionEstablished
    )

    fun start() {
        onStatus("Starting SNDCPY server runtime...")
        onStatus(discovery.advertise(PORT))

        server.start()
        videoStreaming.start { frame ->
            onStatus("Video prototype frame ${frame.frameIndex} generated")
        }

        onStatus("Input prototype ready (TAP, SWIPE, SCROLL, HOME, BACK, KEY:*)")
    }

    fun stop() {
        videoStreaming.stop()
        server.stop()
        onStatus("Runtime stopped")
    }

    fun injectInput(command: String): Boolean {
        val accepted = inputInjector.inject(command)
        onStatus(if (accepted) "Injected command: $command" else "Rejected command: $command")
        return accepted
    }

    private fun onSessionEstablished(session: Session) {
        onStatus("Session established #${session.id} from ${session.remoteAddress}")
    }

    companion object {
        const val PORT = 27183
    }
}
