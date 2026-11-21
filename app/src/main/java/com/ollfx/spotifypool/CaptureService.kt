package com.ollfx.spotifypool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var frameCount = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_START) {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(41, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(41, notification())
            }
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: run { stopSelf(); return START_NOT_STICKY }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            startCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        thread = HandlerThread("pool-frame-reader").also { it.start() }
        val handler = Handler(thread!!.looper)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ source ->
            var image: Image? = null
            try {
                image = source.acquireLatestImage()
                if (image != null) {
                    frameCount++
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    // Frame data is received here. Keep processing lightweight on this worker thread.
                    // Next stage adds table/ball detection and the aiming overlay.
                    if (frameCount % 120L == 0L) {
                        android.util.Log.d("SpotifyPool", "Captured $frameCount frames (${image.width}x${image.height}, ${buffer.remaining()} bytes)")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SpotifyPool", "Frame read failed", e)
            } finally { image?.close() }
        }, handler)
        // Android 14+ requires the callback to be registered before creating the virtual display.
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture(); stopSelf() }
        }, handler)
        display = projection?.createVirtualDisplay(
            "SpotifyPoolCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, handler
        )
    }

    private fun notification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL).setContentTitle("Spotify Pool")
                .setContentText("Screen capture is active").setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Spotify Pool")
                .setContentText("Screen capture is active").setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun stopCapture() {
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        thread?.quitSafely(); thread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() { stopCapture(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.ollfx.spotifypool.START_CAPTURE"
        const val ACTION_STOP = "com.ollfx.spotifypool.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "capture_data"
        private const val CHANNEL = "spotify_pool_capture"
    }
}
