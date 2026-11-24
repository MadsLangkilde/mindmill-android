package dk.langkilde.mindmill.meeting

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import dk.langkilde.mindmill.MindmillApp
import dk.langkilde.mindmill.R
import java.io.ByteArrayOutputStream

/**
 * Screen capture for a live meeting (foreground service, type mediaProjection). Holds one virtual display for the
 * whole meeting and turns its latest frame into a JPEG on request: from the app, or from the notification while
 * another app is on screen.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> { (application as MindmillApp).meeting.captureScreen(); return START_NOT_STICKY }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val started = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }.isSuccess
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (!started || data == null || projection != null) {
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }
        runCatching { begin(code, data) }.onFailure {
            (application as MindmillApp).meeting.screenCaptureEnded("Screen capture couldn't start.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: error("no projection")
        val t = HandlerThread("screen-capture").apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Handler(Looper.getMainLooper()).post { stopSelf() }
            }
        }, h)
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
        val scale = if (maxOf(bounds.first, bounds.second) > 1600) 2 else 1
        val w = bounds.first / scale
        val hgt = bounds.second / scale
        val dpi = resources.displayMetrics.densityDpi / scale
        val r = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        reader = r
        display = p.createVirtualDisplay(
            "mindmill-meeting", w, hgt, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h,
        )
        projection = p
        instance = this
        (application as MindmillApp).meeting.screenCaptureStarted()
    }

    /** The current screen as a JPEG, or null when nothing has been drawn yet. */
    fun capture(done: (ByteArray?) -> Unit) {
        val h = handler ?: return done(null)
        h.post {
            val image = runCatching { reader?.acquireLatestImage() }.getOrNull()
            if (image == null) { done(null); return@post }
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * image.width
                val padded = createBitmap(image.width + rowPadding / pixelStride, image.height)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 72, out)
                if (bitmap !== padded) padded.recycle()
                bitmap.recycle()
                done(out.toByteArray())
            } catch (_: Exception) {
                done(null)
            } finally {
                image.close()
            }
        }
    }

    override fun onDestroy() {
        instance = null
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        display = null
        reader = null
        projection = null
        (application as MindmillApp).meeting.screenCaptureEnded(null)
        super.onDestroy()
    }

    private fun notification() = run {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Meeting screen capture", NotificationManager.IMPORTANCE_LOW))
        val capture = PendingIntent.getService(
            this, 2, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_CAPTURE), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mindmill)
            .setContentTitle("Mindmill meeting")
            .setContentText("Screen capture is on. Tap Capture to send the screen to the notes.")
            .setOngoing(true)
            .addAction(0, "Capture", capture)
            .addAction(0, "Stop capture", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "screen"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_CAPTURE = "dk.langkilde.mindmill.CAPTURE"
        private const val ACTION_STOP = "dk.langkilde.mindmill.STOP_CAPTURE"

        @Volatile var instance: ScreenCaptureService? = null
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}
