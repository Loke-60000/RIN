// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.kaiwa.R
import java.io.ByteArrayOutputStream

/**
 * Grabs a single screenshot on demand, then immediately releases the projection — the privacy-
 * friendly "GrapheneOS" approach: the screen is only captured the instant the user explicitly asks
 * the assistant to see it, never mirrored continuously. The captured frame lands in
 * [ScreenContextHolder.screenshot] and [ScreenCaptureManager] is bumped so the popup can react.
 */
class ScreenCaptureService : Service() {
  private var projection: MediaProjection? = null
  private var imageReader: ImageReader? = null
  private var virtualDisplay: VirtualDisplay? = null
  private val handler = Handler(Looper.getMainLooper())
  private var width = 0
  private var height = 0

  override fun onCreate() {
    super.onCreate()
    startForeground(NOTIFICATION_ID, buildNotification())
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
    val resultData = intent?.let { dataOf(it) }
    if (resultCode == 0 || resultData == null) {
      stopSelf()
      return START_NOT_STICKY
    }
    runCatching { beginCapture(resultCode, resultData) }
      .onFailure {
        Log.e(TAG, "Screen capture failed", it)
        finish()
      }
    return START_NOT_STICKY
  }

  private fun beginCapture(resultCode: Int, resultData: Intent) {
    val metrics = resources.displayMetrics
    width = metrics.widthPixels
    height = metrics.heightPixels

    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val proj = mpm.getMediaProjection(resultCode, resultData) ?: run { finish(); return }
    proj.registerCallback(
      object : MediaProjection.Callback() {
        override fun onStop() = finish()
      },
      handler,
    )
    val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    virtualDisplay =
      proj.createVirtualDisplay(
        "kaiwa-shot",
        width,
        height,
        metrics.densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        reader.surface,
        null,
        handler,
      )
    imageReader = reader
    projection = proj
    // Let the mirror settle (the consent dialog is dismissing) before grabbing a frame.
    handler.postDelayed({ grabAndFinish() }, SETTLE_MS)
  }

  private fun grabAndFinish() {
    runCatching {
        val bytes = imageReader?.let { readFrame(it) }
        if (bytes != null) {
          ScreenContextHolder.screenshot = bytes
          ScreenCaptureManager.markCaptured()
          Log.d(TAG, "Captured one-shot screenshot ${bytes.size} bytes")
        } else {
          Log.w(TAG, "No frame available for one-shot capture")
        }
      }
      .onFailure { Log.w(TAG, "grab failed", it) }
    finish()
  }

  private fun readFrame(reader: ImageReader): ByteArray? {
    val image = reader.acquireLatestImage() ?: return null
    return try {
      val plane = image.planes[0]
      val rowPadding = plane.rowStride - plane.pixelStride * width
      val bmp =
        Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
      bmp.copyPixelsFromBuffer(plane.buffer)
      val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
      bmp.recycle()
      val scaled = downscale(cropped, MAX_DIMEN)
      if (scaled !== cropped) cropped.recycle()
      ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        scaled.recycle()
        out.toByteArray()
      }
    } finally {
      image.close()
    }
  }

  private fun downscale(bmp: Bitmap, max: Int): Bitmap {
    val largest = maxOf(bmp.width, bmp.height)
    if (largest <= max) return bmp
    val scale = max.toFloat() / largest
    return Bitmap.createScaledBitmap(
      bmp,
      (bmp.width * scale).toInt(),
      (bmp.height * scale).toInt(),
      true,
    )
  }

  private fun finish() {
    handler.removeCallbacksAndMessages(null)
    virtualDisplay?.release()
    imageReader?.close()
    projection?.stop()
    virtualDisplay = null
    imageReader = null
    projection = null
    stopSelf()
  }

  override fun onDestroy() {
    finish()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun dataOf(intent: Intent): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
      @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
    }

  private fun buildNotification(): android.app.Notification {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_MIN)
          .apply { description = "Briefly active while the assistant takes one screenshot." }
      )
    }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.gemma_logo)
      .setContentTitle("Rin")
      .setContentText("Capturing your screen…")
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setShowWhen(false)
      .build()
  }

  companion object {
    private const val TAG = "ScreenCaptureService"
    private const val CHANNEL_ID = "screen_capture"
    private const val NOTIFICATION_ID = 4243
    private const val MAX_DIMEN = 1280
    private const val SETTLE_MS = 400L
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_RESULT_DATA = "result_data"

    fun start(context: Context, resultCode: Int, resultData: Intent) {
      val intent =
        Intent(context, ScreenCaptureService::class.java)
          .putExtra(EXTRA_RESULT_CODE, resultCode)
          .putExtra(EXTRA_RESULT_DATA, resultData)
      ContextCompat.startForegroundService(context, intent)
    }
  }
}
