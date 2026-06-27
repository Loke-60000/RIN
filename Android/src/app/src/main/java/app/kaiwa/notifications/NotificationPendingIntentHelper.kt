// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Builds the broadcast [PendingIntent] that the alarm manager fires for a scheduled notification.
 * The EXTRA_* keys below double as the wire format for the intent payload, so their literal values
 * must stay stable across versions.
 */
object NotificationPendingIntentHelper {
  const val EXTRA_ID = "id"
  const val EXTRA_TITLE = "title"
  const val EXTRA_MESSAGE = "message"
  const val EXTRA_DEEPLINK = "deeplink"
  const val EXTRA_REPEAT_DAILY = "repeat_daily"
  const val EXTRA_HOUR = "hour"
  const val EXTRA_MINUTE = "minute"
  const val EXTRA_CHANNEL_ID = "channel_id"
  const val EXTRA_CHANNEL_NAME = "channel_name"

  fun buildNotificationPendingIntent(
    context: Context,
    id: String,
    title: String,
    message: String,
    deeplink: String,
    repeatDaily: Boolean,
    hour: Int,
    minute: Int,
    channelId: String,
    channelName: String,
  ): PendingIntent {
    // Resolve the receiver reflectively so the helper stays decoupled from the receiver type.
    val receiver = Class.forName("app.kaiwa.notifications.NotificationReceiver")
    val intent =
      Intent(context, receiver).apply {
        putExtra(EXTRA_ID, id)
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_MESSAGE, message)
        putExtra(EXTRA_DEEPLINK, deeplink)
        putExtra(EXTRA_REPEAT_DAILY, repeatDaily)
        putExtra(EXTRA_HOUR, hour)
        putExtra(EXTRA_MINUTE, minute)
        putExtra(EXTRA_CHANNEL_ID, channelId)
        putExtra(EXTRA_CHANNEL_NAME, channelName)
      }
    // Key the request code off the id so re-scheduling the same notification replaces its alarm.
    return PendingIntent.getBroadcast(
      context,
      id.hashCode(),
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }
}
