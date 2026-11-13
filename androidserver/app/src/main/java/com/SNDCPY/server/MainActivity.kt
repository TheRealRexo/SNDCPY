package com.SNDCPY.server

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.SNDCPY.server.databinding.ActivityMainBinding
import com.SNDCPY.server.runtime.RemoteControlRuntime

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding: ActivityMainBinding
        get() = _binding!!

    private lateinit var runtime: RemoteControlRuntime

    private val projectionRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                runtime.onProjectionApproved(result.resultCode, result.data!!)
            } else {
                appendStatus("Screen capture permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        runtime = RemoteControlRuntime(applicationContext) { status: String ->
            runOnUiThread { appendStatus(status) }
        }

        runtime.start()

        // Auto-issue the first pairing code on startup so it is visible immediately.
        refreshPairingCode()

        binding.refreshPairingButton.setOnClickListener { refreshPairingCode() }
        binding.startCaptureButton.setOnClickListener { requestScreenCapture() }
        binding.stopCaptureButton.setOnClickListener { runtime.stopCapture() }
        binding.enableInputButton.setOnClickListener { openAccessibilitySettings() }

        // Prompt pairing approval callback — UI shows a dialog and resolves the latch.
        runtime.onPromptPairingRequested = { fingerprint ->
            showPromptPairingDialog(fingerprint, runtime)
        }
    }

    override fun onDestroy() {
        if (this::runtime.isInitialized) {
            runtime.stop()
        }
        _binding = null
        super.onDestroy()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionRequest.launch(manager.createScreenCaptureIntent())
    }

    private fun refreshPairingCode() {
        val code = runtime.issuePairingCode()
        binding.pairingCodeView.text = formatCode(code)
    }

    private fun formatCode(code: String): String {
        // Display as plain 6-digit number (no spacing)
        if (code.length != 6) return code
        return code
    }

    /**
     * Shows a Bluetooth-style prompt dialog for a new device requesting to pair.
     * The user must approve or reject; the PairingManager latch is resolved accordingly.
     */
    private fun showPromptPairingDialog(fingerprint: String, runtime: RemoteControlRuntime) {
        val pairingManager = runtime.pairingManager
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Pairing Request")
                .setMessage("New device wants to pair.\nFingerprint: ${fingerprint.take(16)}…")
                .setPositiveButton("Allow") { _, _ -> pairingManager.approvePromptPairing() }
                .setNegativeButton("Reject") { _, _ -> pairingManager.rejectPromptPairing() }
                .setOnCancelListener { pairingManager.rejectPromptPairing() }
                .show()
        }
    }

    private fun openAccessibilitySettings() {
        // Only jump to accessibility settings if the SNDCPY service is not enabled.
        if (runtime.isRemoteInputEnabled()) {
            appendStatus("Remote input is already enabled")
            return
        }
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun appendStatus(status: String) {
        binding.statusTextView.append("$status\n")
        binding.statusScrollView.post {
            binding.statusScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
