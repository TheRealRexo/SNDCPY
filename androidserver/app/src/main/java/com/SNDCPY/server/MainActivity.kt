package com.SNDCPY.server

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
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

    private val pickApk = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            SelfUpdater.ensureInstallPermission(this,
                onGranted = {
                    val ok = SelfUpdater.installFromUri(this, uri)
                    appendStatus(if (ok) "Installing SNDCPY update…" else "Install failed")
                },
                onDenied = {
                    appendStatus("Grant 'install unknown apps' permission to update")
                }
            )
        } else {
            appendStatus("Update cancelled")
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

        binding.refreshPairingButton.setOnClickListener { refreshPairingCode() }
        binding.startCaptureButton.setOnClickListener { requestScreenCapture() }
        binding.stopCaptureButton.setOnClickListener { runtime.stopCapture() }
        binding.enableInputButton.setOnClickListener { openAccessibilitySettings() }
        binding.updateButton.setOnClickListener {
            appendStatus("Opening APK picker for self-update…")
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/vnd.android.package-archive"
            }
            pickApk.launch(intent)
        }

        // Prompt pairing approval callback — UI shows a dialog and resolves the latch.
        runtime.onPromptPairingRequested = { fingerprint ->
            showPromptPairingDialog(fingerprint, runtime)
        }

        // Connect approval callback — require approval BEFORE issuing/displaying pairing code.
        runtime.onConnectRequestReceived = { fingerprint ->
            showConnectApprovalDialog(fingerprint, runtime)
        }

        // Code display callback — show the 6-digit code in a dialog after approval.
        runtime.onPairingCodeIssued = { code ->
            runOnUiThread { showPairingCodeDialog(code) }
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
                .setPositiveButton("Approve Connection") { _, _ -> pairingManager.approvePromptPairing() }
                .setNegativeButton("Deny Connection") { _, _ -> pairingManager.rejectPromptPairing() }
                .setOnCancelListener { pairingManager.rejectPromptPairing() }
                .show()
        }
    }

    /**
     * Shows an approval dialog for an incoming unpaired client connection.
     * The user must Allow or Reject before a pairing code is issued and shown.
     */
    private fun showConnectApprovalDialog(fingerprint: String, runtime: RemoteControlRuntime) {
        val pairingManager = runtime.pairingManager
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Incoming pairing request")
                .setMessage("A new device wants to pair.\nFingerprint: ${fingerprint.take(16)}…\nApprove?")
                .setPositiveButton("Start Pairing") { _, _ -> pairingManager.approveConnectApproval() }
                .setNegativeButton("Decline Connection") { _, _ -> pairingManager.rejectConnectApproval() }
                .setOnCancelListener { pairingManager.rejectConnectApproval() }
                .show()
        }
    }

    /**
     * Shows the pairing code in a dialog so the server user can read it.
     * Called automatically after connect approval is granted.
     */
    private fun showPairingCodeDialog(code: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Pairing Code")
                .setMessage("Enter this code on your client device:")
                .setSingleChoiceItems(arrayOf(code), -1) { _, _ -> }
                .setPositiveButton("Dismiss") { d, _ -> d.dismiss() }
                .setCancelable(false)
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