package com.sndcpy.client

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sndcpy.client.R
import java.net.InetSocketAddress

/**
 * Full SNDCPY client: LAN discovery, secure pairing, H.264 video,
 * multi-touch input and keyboard passthrough.
 */
class ClientActivity : AppCompatActivity() {

    private var client: SndcpyClient? = null
    private var discovery: DiscoveryClient? = null

    private lateinit var hostEditText: EditText
    private lateinit var portEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var homeButton: Button
    private lateinit var backButton: Button
    private lateinit var recentsButton: Button
    private lateinit var statusTextView: android.widget.TextView
    private lateinit var touchSurfaceView: TouchSurfaceView
    private lateinit var videoSurfaceView: VideoSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        hostEditText = findViewById(R.id.hostEditText)
        portEditText = findViewById(R.id.portEditText)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        homeButton = findViewById(R.id.homeButton)
        backButton = findViewById(R.id.backButton)
        recentsButton = findViewById(R.id.recentsButton)
        statusTextView = findViewById(R.id.statusTextView)
        touchSurfaceView = findViewById(R.id.touchSurfaceView)
        videoSurfaceView = findViewById(R.id.videoSurfaceView)

        hostEditText.setText("192.168.1.42")
        portEditText.setText("27183")

        // Touch surface → encrypted control commands.
        touchSurfaceView.listener = object : TouchSurfaceView.Listener {
            override fun onPointerDown(pointerId: Int, x: Float, y: Float) {
                client?.sendControl("POINTER_DOWN $pointerId $x $y")
            }

            override fun onPointerMove(pointerId: Int, x: Float, y: Float) {
                client?.sendControl("POINTER_MOVE $pointerId $x $y")
            }

            override fun onPointerUp(pointerId: Int) {
                client?.sendControl("POINTER_UP $pointerId")
            }
        }

        connectButton.setOnClickListener { startConnect() }
        disconnectButton.setOnClickListener { disconnect() }
        homeButton.setOnClickListener { client?.sendControl("HOME") }
        backButton.setOnClickListener { client?.sendControl("BACK") }
        recentsButton.setOnClickListener { client?.sendControl("RECENTS") }

        startDiscovery()
    }

    private fun startDiscovery() {
        discovery = DiscoveryClient().apply {
            onServerFound = { server ->
                runOnUiThread {
                    hostEditText.setText(server.ip)
                    portEditText.setText(server.port.toString())
                    statusTextView.text = "Found: ${server.name} @ ${server.ip}:${server.port}"
                }
            }
            start()
        }
    }

    private fun startConnect() {
        val host = hostEditText.text.toString().trim()
        val port = portEditText.text.toString().trim().toIntOrNull() ?: 27183
        if (host.isBlank()) {
            toast("Enter a server IP")
            return
        }

        val c = SndcpyClient(host, port)
        client = c
        c.onPairingRequired = { showPairingDialog() }
        c.connect()
        wireListener(c)
    }

    private fun wireListener(c: SndcpyClient) {
        c.listener = object : SndcpyClient.Listener {
            override fun onStateChanged(state: SndcpyClient.State, detail: String?) {
                runOnUiThread {
                    statusTextView.text = detail?.let { "$state — $it" } ?: state.name
                    val connected = state == SndcpyClient.State.CONNECTED ||
                            state == SndcpyClient.State.VIDEO_READY
                    connectButton.text = if (connected) "Disconnect" else "Connect"
                    disconnectButton.isEnabled = connected
                    homeButton.isEnabled = connected
                    backButton.isEnabled = connected
                    recentsButton.isEnabled = connected
                }
            }

            override fun onMessage(text: String) {
                runOnUiThread { statusTextView.text = text }
            }

            override fun onFrameDecoded(envelope: Envelope) {
                runOnUiThread {
                    videoSurfaceView.feed(envelope.payload, envelope.ptsUs)
                }
            }
        }
    }

    private fun showPairingDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_pairing)
        dialog.setTitle("Pair with server")
        val pairingEdit = dialog.findViewById<EditText>(R.id.pairingEdit)
        val pairButton = dialog.findViewById<Button>(R.id.pairButton)
        pairButton.setOnClickListener {
            val code = pairingEdit.text.toString().trim()
            client?.submitPairingCode(code)
            dialog.dismiss()
        }
        dialog.setOnCancelListener { client?.submitPairingCode("") }
        dialog.show()
    }

    private fun disconnect() {
        client?.disconnect()
        client = null
        discovery?.stop()
        discovery = null
        statusTextView.text = "Disconnected"
        connectButton.text = "Connect"
        connectButton.isEnabled = true
        disconnectButton.isEnabled = false
        homeButton.isEnabled = false
        backButton.isEnabled = false
        recentsButton.isEnabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}