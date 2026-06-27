// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.kaiwa.assistant.BackTapService
import app.kaiwa.chat.ChatPrefs
import dagger.hilt.android.EntryPointAccessors

/**
 * Re-arms every persisted alarm once the device finishes booting. Alarms set with [AlarmManager]
 * do not survive a reboot, so we listen for the boot broadcast and ask the schedule manager to
 * register them again.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

    Log.d(TAG, "Boot completed received, rescheduling notifications")
    runCatching {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationScheduleManagerEntryPoint::class.java,
          )
          .notificationScheduleManager()
          .rescheduleAllNotifications()
      }
      .onFailure { Log.e(TAG, "Failed to reschedule notifications on boot", it) }

    // Re-arm the back-tap gesture service if the user left it on (FGS start is allowed from boot).
    runCatching { if (ChatPrefs(context).backTapEnabled) BackTapService.start(context) }
      .onFailure { Log.e(TAG, "Failed to start back-tap service on boot", it) }
  }

  private companion object {
    const val TAG = "BootReceiver"
  }
}
