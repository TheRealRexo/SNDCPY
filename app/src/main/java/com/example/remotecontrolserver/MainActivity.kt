package com.SNDCPY.server

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.SNDCPY.server.databinding.ActivityMainBinding
import com.SNDCPY.server.runtime.RemoteControlRuntime

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!

    private lateinit var runtime: RemoteControlRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        runtime = RemoteControlRuntime { status ->
            runOnUiThread {
                binding.statusTextView.text = status
            }
        }

        runtime.start()
        runtime.injectInput("TAP")
    }

    override fun onDestroy() {
        runtime.stop()
        _binding = null
        super.onDestroy()
    }
}