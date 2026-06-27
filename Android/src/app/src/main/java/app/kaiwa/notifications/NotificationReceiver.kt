// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import dagger.hilt.android.EntryPointAccessors

/**
 * Posts a notification when one of its scheduled alarms fires. The payload arrives as intent
 * extras keyed by the constants in [NotificationPendingIntentHelper]. One-shot notifications are
 * dropped from the persisted schedule once delivered.
 */
class NotificationReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    Log.d(TAG, "onReceive called with intent: $intent")

    val id = intent.stringExtra(NotificationPendingIntentHelper.EXTRA_ID, "")
    val title = intent.stringExtra(NotificationPendingIntentHelper.EXTRA_TITLE, "Scheduled task")
    val message =
      intent.stringExtra(NotificationPendingIntentHelper.EXTRA_MESSAGE, "Time to complete your task!")
    val deeplink = intent.stringExtra(NotificationPendingIntentHelper.EXTRA_DEEPLINK, "")
    val channelId =
      intent.stringExtra(NotificationPendingIntentHelper.EXTRA_CHANNEL_ID, DEFAULT_CHANNEL_ID)
    val channelName =
      intent.stringExtra(NotificationPendingIntentHelper.EXTRA_CHANNEL_NAME, DEFAULT_CHANNEL_NAME)

    try {
      postNotification(context, title, message, deeplink, channelId, channelName)

      val repeats = intent.getBooleanExtra(NotificationPendingIntentHelper.EXTRA_REPEAT_DAILY, false)
      if (!repeats && id.isNotEmpty()) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationScheduleManagerEntryPoint::class.java,
          )
          .notificationScheduleManager()
          .removeNotification(id)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to send notification", e)
    }
  }

  private fun postNotification(
    context: Context,
    title: String,
    message: String,
    deeplink: String,
    channelId: String,
    channelName: String,
  ) {
    val tapIntent =
      Intent(Intent.ACTION_VIEW).apply {
        if (deeplink.isNotEmpty()) data = deeplink.toUri()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val contentIntent =
      PendingIntent.getActivity(
        context,
        0,
        tapIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
        NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
      )
    }

    val notification =
      NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(message)
        .setAutoCancel(true)
        .setContentIntent(contentIntent)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
    manager.notify(System.currentTimeMillis().toInt(), notification)
  }

  private fun Intent.stringExtra(key: String, fallback: String): String =
    getStringExtra(key) ?: fallback

  private companion object {
    const val TAG = "NotificationReceiver"
    const val DEFAULT_CHANNEL_ID = "ai_edge_gallery_notification_channel"
    const val DEFAULT_CHANNEL_NAME = "AI Edge Gallery Notifications"
  }
}
