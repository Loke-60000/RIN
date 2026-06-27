// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.notifications

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import app.kaiwa.proto.ScheduledNotification
import app.kaiwa.proto.ScheduledNotifications
import com.google.protobuf.InvalidProtocolBufferException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the set of notifications the agent has scheduled: it persists them to disk, exposes the
 * current list as a [StateFlow], and keeps the platform alarms in sync as entries are added,
 * removed, or replayed after a reboot. Application-scoped and safe to touch from any thread.
 */
@Singleton
class NotificationScheduleManager
@Inject
constructor(@ApplicationContext private val context: Context) {

  private val storeFile: File
    get() = File(context.filesDir, STORE_FILENAME)

  private val dataStore: DataStore<ScheduledNotifications> =
    DataStoreFactory.create(
      serializer = ScheduledNotificationsSerializer,
      produceFile = { storeFile },
    )

  // Disk reads/writes run here so callers never block on IO.
  private val ioScope = CoroutineScope(Dispatchers.IO)

  private val _scheduledNotifications = MutableStateFlow<List<ScheduledNotification>>(emptyList())
  val scheduledNotifications = _scheduledNotifications.asStateFlow()

  init {
    ioScope.launch {
      runCatching { readPersisted() }.getOrNull()?.let { _scheduledNotifications.value = it }
    }
  }

  /** No-op hook that lets the application eagerly instantiate this singleton at startup. */
  fun initialize() = Unit

  /** Registers an alarm for [notification] and persists it. Returns whether scheduling succeeded. */
  fun scheduleNotification(notification: ScheduledNotification): Boolean {
    if (!armAlarm(notification)) return false
    _scheduledNotifications.update { it + notification }
    persist()
    return true
  }

  /** Replays every persisted notification's alarm. Invoked after the device reboots. */
  fun rescheduleAllNotifications() {
    ioScope.launch {
      try {
        readPersisted()?.forEach { armAlarm(it) }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to reschedule notifications", e)
      }
    }
  }

  /** Cancels the alarm for [id] (if any) and drops it from the persisted schedule. */
  fun removeNotification(id: String) {
    _scheduledNotifications.value
      .firstOrNull { it.id == id }
      ?.let { alarmManager().cancel(it.toPendingIntent()) }
    _scheduledNotifications.update { list -> list.filter { it.id != id } }
    persist()
  }

  private suspend fun readPersisted(): List<ScheduledNotification>? {
    if (!storeFile.exists()) return null
    return storeFile.inputStream().use { ScheduledNotificationsSerializer.readFrom(it) }.notificationList
  }

  private fun persist() {
    ioScope.launch {
      dataStore.updateData {
        ScheduledNotifications.newBuilder()
          .addAllNotification(_scheduledNotifications.value)
          .build()
      }
    }
  }

  /**
   * Schedules the platform alarm for [notification]. A notification carrying an explicit date fires
   * at that date/time; one without fires at the given time today, rolling to tomorrow when that
   * moment has already passed. Daily-repeating notifications register a repeating alarm.
   */
  private fun armAlarm(notification: ScheduledNotification): Boolean {
    val fireAt =
      Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis()
        val hasDate = notification.hasYear() && notification.hasMonth() && notification.hasDay()
        if (hasDate) {
          set(Calendar.YEAR, notification.year)
          set(Calendar.MONTH, notification.month - 1)
          set(Calendar.DAY_OF_MONTH, notification.day)
        }
        set(Calendar.HOUR_OF_DAY, notification.hour)
        set(Calendar.MINUTE, notification.minute)
        set(Calendar.SECOND, 0)
        // A time-only notification (or a repeating one) in the past simply moves to the next day.
        if (before(Calendar.getInstance()) && (notification.repeatDaily || !hasDate)) {
          add(Calendar.DATE, 1)
        }
      }

    val pendingIntent = notification.toPendingIntent()
    val manager = alarmManager()
    if (notification.repeatDaily) {
      manager.setRepeating(
        AlarmManager.RTC_WAKEUP,
        fireAt.timeInMillis,
        AlarmManager.INTERVAL_DAY,
        pendingIntent,
      )
    } else {
      manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt.timeInMillis, pendingIntent)
    }
    return true
  }

  private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

  private fun ScheduledNotification.toPendingIntent() =
    NotificationPendingIntentHelper.buildNotificationPendingIntent(
      context,
      id,
      title,
      message,
      deeplink,
      repeatDaily,
      hour,
      minute,
      channelId,
      channelName,
    )

  private companion object {
    const val TAG = "NotificationScheduleManager"
    const val STORE_FILENAME = "scheduled_notifications.pb"
  }
}

object ScheduledNotificationsSerializer : Serializer<ScheduledNotifications> {
  override val defaultValue: ScheduledNotifications = ScheduledNotifications.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): ScheduledNotifications =
    try {
      ScheduledNotifications.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    }

  override suspend fun writeTo(t: ScheduledNotifications, output: OutputStream) = t.writeTo(output)
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface NotificationScheduleManagerEntryPoint {
  fun notificationScheduleManager(): NotificationScheduleManager
}
