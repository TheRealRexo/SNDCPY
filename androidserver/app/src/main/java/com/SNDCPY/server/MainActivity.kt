package com.SNDCPY.server

import android.app.Activity
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

        binding.pairingButton.setOnClickListener { runtime.issuePairingCode() }
        binding.startCaptureButton.setOnClickListener { requestScreenCapture() }
        binding.stopCaptureButton.setOnClickListener { runtime.stopCapture() }
        binding.enableInputButton.setOnClickListener { openAccessibilitySettings() }
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

    private fun openAccessibilitySettings() {
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun appendStatus(status: String) {
        binding.statusTextView.append("$status\n")
        binding.statusScrollView.post {
            binding.statusScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
