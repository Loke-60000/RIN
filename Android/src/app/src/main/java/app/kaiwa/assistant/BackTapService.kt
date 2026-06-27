// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.kaiwa.R
import app.kaiwa.chat.ChatPrefs

/**
 * Foreground service that listens for a double-tap on the back of the phone (via [BackTapDetector])
 * and opens the OK Gemma assistant overlay. It's the app-side stand-in for the system "Quick Tap"
 * shortcut, which GrapheneOS doesn't offer for assistants.
 *
 * Launched only while the user's "double-tap back" toggle is on. Continuous sensor access in the
 * background requires a foreground service, hence the persistent low-priority notification.
 */
class BackTapService : Service() {
  private var detector: BackTapDetector? = null
  private var lastLaunchAt = 0L

  override fun onCreate() {
    super.onCreate()
    startForeground(NOTIFICATION_ID, buildNotification())
    applyConfig()
  }

  // Re-reads the sensitivity setting and rebuilds the detector, so the settings slider takes effect
  // immediately (the settings code just re-starts the service after changing the value).
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    applyConfig()
    return START_STICKY
  }

  private fun applyConfig() {
    detector?.stop()
    val threshold = BackTapDetector.thresholdForSensitivity(ChatPrefs(this).backTapSensitivity)
    val backTap = BackTapDetector(this, tapThreshold = threshold, onDoubleTap = ::onDoubleTap)
    if (!backTap.isAvailable) {
      Log.w(TAG, "No accelerometer available; stopping back-tap service")
      stopSelf()
      return
    }
    backTap.start()
    detector = backTap
  }

  override fun onDestroy() {
    detector?.stop()
    detector = null
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun onDoubleTap() {
    // Debounce so one gesture doesn't stack multiple overlays.
    val now = System.currentTimeMillis()
    if (now - lastLaunchAt < LAUNCH_DEBOUNCE_MS) return
    lastLaunchAt = now

    vibrate()
    val intent =
      Intent(this, AssistantOverlayActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    try {
      startActivity(intent)
    } catch (e: Exception) {
      // Background activity starts need the "display over other apps" grant; the toggle asks for it.
      Log.w(TAG, "Failed to launch assistant from back-tap", e)
    }
  }

  private fun vibrate() {
    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    if (vibrator.hasVibrator()) {
      vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }
  }

  private fun buildNotification(): android.app.Notification {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Double-tap gesture", NotificationManager.IMPORTANCE_MIN)
          .apply { description = "Lets Rin watch for a double-tap on the back of the phone." }
      )
    }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.gemma_logo)
      .setContentTitle("Rin")
      .setContentText("Double-tap the back to open the assistant")
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setShowWhen(false)
      .build()
  }

  companion object {
    private const val TAG = "BackTapService"
    private const val CHANNEL_ID = "back_tap_gesture"
    private const val NOTIFICATION_ID = 4242
    private const val LAUNCH_DEBOUNCE_MS = 1500L

    fun start(context: Context) {
      ContextCompat.startForegroundService(context, Intent(context, BackTapService::class.java))
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, BackTapService::class.java))
    }
  }
}
