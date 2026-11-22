package com.ollfx.spotifypool

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.ollfx.spotifypool.action.START"
        const val ACTION_STOP = "com.ollfx.spotifypool.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data_intent"
        const val EXTRA_DATA_INTENT = EXTRA_DATA
        private const val CHANNEL_ID = "spotify_pool_overlay"
        private const val NOTIFICATION_ID = 1042
        private const val SAMPLE_STEP_PX = 4
    }

    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var displayManager: DisplayManager
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0
    private var overlayView: TrajectoryOverlayView? = null

    private val workerThread = HandlerThread("PoolVisionThread").apply { start() }
    private lateinit var workerHandler: Handler

    @Volatile
    private var latestPlan: PoolTracker.ShotPlan? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        workerHandler = Handler(workerThread.looper)
        createNotificationChannel()
        promoteToForeground()
        if (Settings.canDrawOverlays(this)) attachOverlay()
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                ?: intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
                ?: intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode == Activity.RESULT_OK && dataIntent != null && mediaProjection == null) {
            setupProjection(resultCode, dataIntent)
        }
        return START_STICKY
    }

    private fun setupProjection(resultCode: Int, dataIntent: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        captureDensity = metrics.densityDpi

        val reader = createImageReader(captureWidth, captureHeight)
        imageReader = reader

        val projection = projectionManager.getMediaProjection(resultCode, dataIntent)
        mediaProjection = projection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                displayListener?.let {
                    displayManager.unregisterDisplayListener(it)
                }
                displayListener = null
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
                mediaProjection = null
                stopSelf()
            }
        }

        projection.registerCallback(callback, workerHandler)
        projectionCallback = callback

        virtualDisplay = projection.createVirtualDisplay(
            "SpotifyPoolCapture",
            captureWidth,
            captureHeight,
            captureDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            workerHandler
        )

        registerDisplayListener()
    }

    private fun createImageReader(width: Int, height: Int): ImageReader {
        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rawWidth = plane.rowStride / plane.pixelStride
                val frameWidth = image.width
                val frameHeight = image.height
                val buffer = plane.buffer
                buffer.rewind()

                val bmp = Bitmap.createBitmap(
                    rawWidth,
                    frameHeight,
                    Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)

                val cropWidth = minOf(frameWidth, rawWidth)
                val cropped = if (rawWidth != cropWidth) {
                    Bitmap.createBitmap(bmp, 0, 0, cropWidth, frameHeight).also {
                        bmp.recycle()
                    }
                } else {
                    bmp
                }

                try {
                    latestPlan = PoolTracker.analyzeFrame(cropped, SAMPLE_STEP_PX)
                    overlayView?.postInvalidate()
                } finally {
                    if (!cropped.isRecycled) cropped.recycle()
                }
            } finally {
                image.close()
            }
        }, workerHandler)

        return reader
    }

    private fun registerDisplayListener() {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                @Suppress("DEPRECATION")
                val defaultDisplayId = windowManager.defaultDisplay.displayId
                if (displayId == defaultDisplayId) {
                    refreshCaptureSize()
                }
            }
        }

        displayListener = listener
        displayManager.registerDisplayListener(listener, workerHandler)
    }

    private fun refreshCaptureSize() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val newWidth = metrics.widthPixels
        val newHeight = metrics.heightPixels
        val newDensity = metrics.densityDpi

        if (newWidth == captureWidth &&
            newHeight == captureHeight &&
            newDensity == captureDensity
        ) {
            return
        }

        val newReader = createImageReader(newWidth, newHeight)
        val oldReader = imageReader

        virtualDisplay?.resize(newWidth, newHeight, newDensity)
        virtualDisplay?.setSurface(newReader.surface)

        imageReader = newReader
        captureWidth = newWidth
        captureHeight = newHeight
        captureDensity = newDensity

        oldReader?.close()
        latestPlan = null
        overlayView?.postInvalidate()
    }

    private fun attachOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val view = TrajectoryOverlayView(this)
        overlayView = view
        windowManager.addView(view, params)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pool Trajectory Overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Overlay Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }

    override fun onDestroy() {
        projectionCallback?.let { callback ->
            mediaProjection?.unregisterCallback(callback)
        }
        projectionCallback = null
        displayListener?.let { displayManager.unregisterDisplayListener(it) }
        displayListener = null
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
        }
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null


    private inner class TrajectoryOverlayView(context: Context) : View(context) {
        private val cueSolidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.MAGENTA
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }

        private val cueBouncePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.MAGENTA
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }

        private val objectAimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.MAGENTA
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }

        private val pocketRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.MAGENTA
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        private val ballMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.MAGENTA
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        private val ghostBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.MAGENTA
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }

        private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.MAGENTA
            textSize = 22f
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            val plan = latestPlan ?: return
            if (plan.frameWidth <= 0 || plan.frameHeight <= 0) return

            val save = canvas.save()

            // Map capture coordinates into the overlay's actual screen position.
            val screenLocation = IntArray(2)
            getLocationOnScreen(screenLocation)

            val displayWidth = captureWidth.takeIf { it > 0 } ?: plan.frameWidth
            val displayHeight = captureHeight.takeIf { it > 0 } ?: plan.frameHeight

            canvas.translate(
                -screenLocation[0].toFloat(),
                -screenLocation[1].toFloat()
            )

            val scaleX = displayWidth.toFloat() / plan.frameWidth.toFloat()
            val scaleY = displayHeight.toFloat() / plan.frameHeight.toFloat()
            canvas.scale(scaleX, scaleY)

            for (ball in plan.balls) {
                canvas.drawCircle(
                    ball.center.x,
                    ball.center.y,
                    ball.radius + 2f,
                    ballMarkerPaint
                )
            }

            for (pocket in plan.pockets) {
                canvas.drawCircle(
                    pocket.x,
                    pocket.y,
                    plan.ballRadius * 1.4f,
                    pocketRingPaint
                )
            }

            for (segment in plan.cueSegments) {
                canvas.drawLine(
                    segment.start.x,
                    segment.start.y,
                    segment.end.x,
                    segment.end.y,
                    if (segment.isRailBounce) cueBouncePaint else cueSolidPaint
                )
            }

            plan.impactPoint?.let {
                canvas.drawCircle(
                    it.x,
                    it.y,
                    plan.ballRadius,
                    ghostBallPaint
                )
            }

            for (segment in plan.deflectedCueSegments) {
                canvas.drawLine(
                    segment.start.x,
                    segment.start.y,
                    segment.end.x,
                    segment.end.y,
                    cueBouncePaint
                )
            }

            for (segment in plan.objectSegments) {
                canvas.drawLine(
                    segment.start.x,
                    segment.start.y,
                    segment.end.x,
                    segment.end.y,
                    objectAimPaint
                )
            }

            plan.objectSegments.lastOrNull()?.let {
                canvas.drawCircle(
                    it.end.x,
                    it.end.y,
                    plan.ballRadius * 0.8f,
                    pocketRingPaint
                )
            }

            canvas.drawText(
                "POOL | BALLS ${plan.balls.size} | CUE ${if (plan.cueBall != null) "FOUND" else "NOT FOUND"} | ${plan.angleDeg.toInt()}°",
                plan.tableRect.left,
                maxOf(28f, plan.tableRect.top - 12f),
                hudPaint
            )

            canvas.restoreToCount(save)
        }
    }
}
