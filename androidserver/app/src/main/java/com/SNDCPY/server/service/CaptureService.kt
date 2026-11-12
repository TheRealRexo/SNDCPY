package com.SNDCPY.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.SNDCPY.server.R
import com.SNDCPY.server.runtime.RuntimeConfig
import com.SNDCPY.server.streaming.AudioStreamer
import com.SNDCPY.server.streaming.ScreenCaptureStreamer

/**
 * Foreground service that owns the MediaProjection and the capture streamer,
 * so streaming survives Activity recreation and shows the mandatory
 * screen-capture notification.
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var streamer: ScreenCaptureStreamer? = null
    private var audioStreamer: AudioStreamer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            Log.e(TAG, "Capture service started without projection consent")
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            val mediaProjection = if (Build.VERSION.SDK_INT >= 34) {
                manager.getMediaProjection(resultCode, resultData)
            } else {
                @Suppress("DEPRECATION")
                manager.getMediaProjection(resultCode, resultData)
            }
            projection = mediaProjection

            // API 34+ requires a callback before creating the virtual display.
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system/user")
                    shutdownStreamer()
                    stopSelf()
                }
            }, null)

            val metrics = displayMetrics()
            streamer = ScreenCaptureStreamer(
                projection = mediaProjection!!,
                displayWidthPixels = metrics.widthPixels,
                displayHeightPixels = metrics.heightPixels,
                displayDensityDpi = metrics.densityDpi,
                config = RuntimeConfig.captureConfig()
            ).also { it.start() }

            if (Build.VERSION.SDK_INT >= 29) {
                audioStreamer = AudioStreamer(mediaProjection!!).also { it.start() }
            } else {
                Log.i(TAG, "Audio playback capture unsupported below Android 10")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire projection or start capture", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        shutdownStreamer()
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    private fun shutdownStreamer() {
        streamer?.stop()
        streamer = null
        audioStreamer?.stop()
        audioStreamer = null
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.capture_notification_title))
                .setContentText(getString(R.string.capture_notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.capture_notification_title))
                .setContentText(getString(R.string.capture_notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds.let { bounds ->
                metrics.widthPixels = bounds.width()
                metrics.heightPixels = bounds.height()
                metrics.densityDpi = resources.configuration.densityDpi
            }
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "sndcpy_capture"
        private const val NOTIFICATION_ID = 27183
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun intent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, CaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
    }
}
