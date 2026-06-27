// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.customtasks.mobileactions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import app.kaiwa.R
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "AGMAExecutor"

private const val ONE_HOUR_MS = 60 * 60 * 1000L

/**
 * Carries out a recognized [Action] against the device. This is the one place that actually
 * performs the side effect, shared by the Mobile Actions demo screen and the assistant overlay.
 * Returns an empty string on success or a human-readable error otherwise.
 */
object MobileActionExecutor {
  fun perform(action: Action, context: Context): String =
    when (action) {
      is FlashlightOnAction -> setFlashlight(context, isEnabled = true)
      is FlashlightOffAction -> setFlashlight(context, isEnabled = false)
      is CreateContactAction ->
        createContact(context, action.firstName, action.lastName, action.phoneNumber, action.email)
      is SendEmailAction -> sendEmail(context, action.to, action.subject, action.body)
      is ShowLocationOnMap -> showLocationOnMap(context, action.location)
      is OpenWifiSettingsAction -> openWifiSettings(context)
      is CreateCalendarEventAction -> createCalendarEvent(context, action.datetime, action.title)
      is LaunchAppAction -> launchApp(context, action.appName)
      else -> ""
    }

  fun setFlashlight(context: Context, isEnabled: Boolean): String {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val torchCameraId =
      try {
        cameraManager.cameraIdList.firstOrNull { id ->
          cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
            ?: false
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to set flashlight", e)
        return e.message ?: context.getString(R.string.unknown_error)
      }

    if (torchCameraId != null) {
      try {
        cameraManager.setTorchMode(torchCameraId, isEnabled)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to set flashlight", e)
        return e.message ?: context.getString(R.string.unknown_error)
      }
    }
    return ""
  }

  /** Opens the best-matching installed, launchable app for [appName]. */
  private fun launchApp(context: Context, appName: String): String =
    runAction(context, "Failed to launch app '$appName'") {
      val pm = context.packageManager
      val query = appName.trim().lowercase()
      val best =
        pm.getInstalledApplications(0)
          .mapNotNull { info ->
            if (pm.getLaunchIntentForPackage(info.packageName) == null) null
            else pm.getApplicationLabel(info).toString() to info
          }
          .minByOrNull { (label, _) ->
            val l = label.lowercase()
            when {
              l == query -> 0
              l.startsWith(query) -> 1
              l.contains(query) -> 2
              else -> 100
            }
          }
      if (best == null || !best.first.lowercase().contains(query)) {
        return "No installed app matching \"$appName\""
      }
      val intent =
        pm.getLaunchIntentForPackage(best.second.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          ?: return "Can't launch \"${best.first}\""
      context.startActivity(intent)
    }

  private fun createContact(
    context: Context,
    firstName: String,
    lastName: String,
    phoneNumber: String,
    email: String,
  ): String =
    runAction(context, "Failed to create contact") {
      val intent =
        Intent(ContactsContract.Intents.Insert.ACTION).apply {
          type = ContactsContract.RawContacts.CONTENT_TYPE
          putExtra(ContactsContract.Intents.Insert.NAME, "$firstName $lastName")
          putExtra(ContactsContract.Intents.Insert.EMAIL, email)
          putExtra(
            ContactsContract.Intents.Insert.EMAIL_TYPE,
            ContactsContract.CommonDataKinds.Email.TYPE_WORK,
          )
          putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
          putExtra(
            ContactsContract.Intents.Insert.PHONE_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
          )
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      context.startActivity(intent)
    }

  private fun sendEmail(context: Context, to: String, subject: String, body: String): String =
    runAction(context, "Failed to send email") {
      val intent =
        Intent(Intent.ACTION_SEND).apply {
          data = "mailto:".toUri()
          type = "text/plain"
          putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
          putExtra(Intent.EXTRA_SUBJECT, subject)
          putExtra(Intent.EXTRA_TEXT, body)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      context.startActivity(intent)
    }

  private fun showLocationOnMap(context: Context, location: String): String =
    runAction(context, "Failed to show location on map") {
      val encoded = URLEncoder.encode(location, StandardCharsets.UTF_8.toString())
      val intent =
        Intent(Intent.ACTION_VIEW).apply {
          data = "geo:0,0?q=$encoded".toUri()
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      context.startActivity(intent)
    }

  private fun openWifiSettings(context: Context): String =
    runAction(context, "Failed to open wifi settings") {
      context.startActivity(
        Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      )
    }

  private fun createCalendarEvent(context: Context, datetime: String, title: String): String {
    val startMs =
      try {
        LocalDateTime.parse(datetime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to parse date time: '$datetime'", e)
        System.currentTimeMillis()
      }
    return runAction(context, "Failed to create calendar event") {
      val intent =
        Intent(Intent.ACTION_INSERT).apply {
          data = CalendarContract.Events.CONTENT_URI
          putExtra(CalendarContract.Events.TITLE, title)
          putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
          putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMs + ONE_HOUR_MS)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      context.startActivity(intent)
    }
  }

  /**
   * Runs [block], returning its early-return string if it produces one, an empty string when it
   * completes normally, or the logged error message if it throws.
   */
  private inline fun runAction(context: Context, logMessage: String, block: () -> Unit): String {
    return try {
      block()
      ""
    } catch (e: Exception) {
      Log.e(TAG, logMessage, e)
      e.message ?: context.getString(R.string.unknown_error)
    }
  }
}
