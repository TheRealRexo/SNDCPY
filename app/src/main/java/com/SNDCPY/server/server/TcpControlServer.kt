package com.example.remotecontrolserver.server

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

class TcpControlServer(
    private val port: Int,
    private val sessionManager: SessionManager,
    private val onSessionEstablished: (Session) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return

        running = true
        executor.execute {
            try {
                ServerSocket(port).use { socket ->
                    serverSocket = socket
                    socket.reuseAddress = true
                    socket.soTimeout = 1000
                    Log.i(TAG, "TCP control server started on port $port")

                    while (running) {
                        try {
                            val client = socket.accept()
                            handleClient(client)
                        } catch (_: SocketTimeoutException) {
                            // Polling timeout so stop() can break loop quickly.
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP server failed", e)
            } finally {
                running = false
                serverSocket = null
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
            // Ignore shutdown exception.
        }
    }

    private fun handleClient(client: java.net.Socket) {
        client.use { socket ->
            socket.soTimeout = 3000
            val remoteAddress = socket.inetAddress?.hostAddress ?: "unknown"
            val session = sessionManager.createSession(remoteAddress)
            onSessionEstablished(session)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            val handshake = reader.readLine()?.trim().orEmpty()
            if (handshake == HANDSHAKE_HELLO) {
                writer.println("WELCOME ${session.id}")
            } else {
                writer.println("ERROR expected '$HANDSHAKE_HELLO'")
            }
        }
    }

    companion object {
        private const val TAG = "TcpControlServer"
        const val HANDSHAKE_HELLO = "HELLO SNDCPY"
    }
}
