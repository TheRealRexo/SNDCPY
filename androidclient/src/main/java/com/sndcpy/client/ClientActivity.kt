package com.sndcpy.client

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.sndcpy.client.R
import java.net.InetSocketAddress

/**
 * Full SNDCPY client: LAN discovery, secure pairing, H.264 video,
 * multi-touch input and keyboard passthrough.
 * Supports 3 pairing methods: CODE, BLUETOOTH (mutual confirmation), USB.
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
    private lateinit var statusTextView: TextView
    private lateinit var touchSurfaceView: TouchSurfaceView
    private lateinit var videoSurfaceView: VideoSurfaceView

    // Pairing method selector
    private lateinit var methodCodeBtn: Button
    private lateinit var methodBluetoothBtn: Button
    private lateinit var methodUsbBtn: Button
    private var selectedMethod: SndcpyClient.PairingMethod = SndcpyClient.PairingMethod.CODE

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

        // Pairing method selector buttons
        methodCodeBtn = findViewById(R.id.methodCodeBtn)
        methodBluetoothBtn = findViewById(R.id.methodBluetoothBtn)
        methodUsbBtn = findViewById(R.id.methodUsbBtn)

        // Initialize method selector - highlight default (Code)
        selectMethod(SndcpyClient.PairingMethod.CODE)

        hostEditText.setText("192.168.1.42")
        portEditText.setText("27183")

        // Touch surface to encrypted control commands.
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

        methodCodeBtn.setOnClickListener { selectMethod(SndcpyClient.PairingMethod.CODE) }
        methodBluetoothBtn.setOnClickListener { selectMethod(SndcpyClient.PairingMethod.BLUETOOTH) }
        methodUsbBtn.setOnClickListener { selectMethod(SndcpyClient.PairingMethod.USB) }

        connectButton.setOnClickListener { startConnect() }
        disconnectButton.setOnClickListener { disconnect() }
        homeButton.setOnClickListener { client?.sendControl("HOME") }
        backButton.setOnClickListener { client?.sendControl("BACK") }
        recentsButton.setOnClickListener { client?.sendControl("RECENTS") }

        startDiscovery()
    }

    private fun selectMethod(method: SndcpyClient.PairingMethod) {
        selectedMethod = method
        // Update button visual states
        methodCodeBtn.isSelected = (method == SndcpyClient.PairingMethod.CODE)
        methodBluetoothBtn.isSelected = (method == SndcpyClient.PairingMethod.BLUETOOTH)
        methodUsbBtn.isSelected = (method == SndcpyClient.PairingMethod.USB)

        // Pairing dialogs handle all UI — no permanent input rows needed.
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

        client = SndcpyClient(host, port)
        client?.onPairingRequired = {
            runOnUiThread {
                when (selectedMethod) {
                    SndcpyClient.PairingMethod.CODE -> showCodePairingDialog()
                    SndcpyClient.PairingMethod.BLUETOOTH -> showBluetoothPairingDialog()
                    SndcpyClient.PairingMethod.USB -> showUsbPairingDialog()
                }
            }
        }
        client?.connect()
        wireListener(client!!)
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

    private fun showCodePairingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pairing, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter Pairing Code")
            .setMessage("Enter the 6-digit code shown on the server")
            .setView(dialogView)
            .setPositiveButton("Confirm Code") { d, _ ->
                val pairingEdit = dialogView.findViewById<EditText>(R.id.pairingEdit)
                val code = pairingEdit.text.toString().trim()
                client?.submitPairingCode(code)
                d.dismiss()
            }
            .setNegativeButton("Cancel Pairing") { d, _ ->
                client?.submitPairingCode("")
                d.dismiss()
            }
            .setOnCancelListener { d -> client?.submitPairingCode(""); d.dismiss() }
            .create()
        dialog.show()
    }

    private fun showBluetoothPairingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pairing, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Bluetooth Pairing")
            .setMessage("Server approved. Enter the 6-digit confirm code to complete pairing.")
            .setView(dialogView)
            .setPositiveButton("Confirm Code") { d, _ ->
                val pairingEdit = dialogView.findViewById<EditText>(R.id.pairingEdit)
                val code = pairingEdit.text.toString().trim()
                client?.submitPairingCode(code)
                d.dismiss()
            }
            .setNegativeButton("Cancel Connection") { d, _ ->
                client?.submitPairingCode("")
                d.dismiss()
            }
            .setOnCancelListener { d -> client?.submitPairingCode(""); d.dismiss() }
            .create()
        dialog.show()
    }

    private fun showUsbPairingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pairing, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("USB Pairing")
            .setMessage("Enter the 6-digit code shown on the server")
            .setView(dialogView)
            .setPositiveButton("Confirm Code") { d, _ ->
                val pairingEdit = dialogView.findViewById<EditText>(R.id.pairingEdit)
                val code = pairingEdit.text.toString().trim()
                client?.submitPairingCode(code)
                d.dismiss()
            }
            .setNegativeButton("Cancel Pairing") { d, _ ->
                client?.submitPairingCode("")
                d.dismiss()
            }
            .setOnCancelListener { d -> client?.submitPairingCode(""); d.dismiss() }
            .create()
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