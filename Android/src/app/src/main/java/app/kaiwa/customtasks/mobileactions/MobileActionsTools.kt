// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.customtasks.mobileactions

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

private const val TAG = "AGMATools"

/**
 * The tool surface the model can call to drive on-device actions. Each method records the
 * requested [Action] via [onFunctionCalled] for later execution and returns a confirmation map
 * back to the model. Tool/param descriptions are part of the prompt contract.
 */
class MobileActionsTools(val onFunctionCalled: (Action) -> Unit) : ToolSet {

  @Tool(description = "Turns the flashlight on")
  fun turnOnFlashlight(): Map<String, String> {
    Log.d(TAG, "turn on flashlight")
    onFunctionCalled(FlashlightOnAction())
    return success()
  }

  @Tool(description = "Turns the flashlight off")
  fun turnOffFlashlight(): Map<String, String> {
    Log.d(TAG, "turn off flashlight")
    onFunctionCalled(FlashlightOffAction())
    return success()
  }

  @Tool(description = "Creates a contact in the phone's contact list.")
  fun createContact(
    @ToolParam(description = "The first name of the contact.") firstName: String,
    @ToolParam(description = "The last name of the contact.") lastName: String,
    @ToolParam(description = "The phone number of the contact.") phoneNumber: String,
    @ToolParam(description = "The email address of the contact.") email: String,
  ): Map<String, String> {
    Log.d(
      TAG,
      "create contact. First name: '$firstName', last name: '$lastName', phone number: '$phoneNumber', email: '$email'",
    )
    onFunctionCalled(CreateContactAction(firstName, lastName, phoneNumber, email))
    return success(
      "first_name" to firstName,
      "last_name" to lastName,
      "phone_number" to phoneNumber,
      "email" to email,
    )
  }

  @Tool(description = "Sends an email.")
  fun sendEmail(
    @ToolParam(description = "The email address of the recipient.") to: String,
    @ToolParam(description = "The subject of the email.") subject: String,
    @ToolParam(description = "The body of the email.") body: String,
  ): Map<String, String> {
    Log.d(TAG, "send email. To: '$to', subject: '$subject', body: '$body'")
    onFunctionCalled(SendEmailAction(to, subject, body))
    return success("to" to to, "subject" to subject, "body" to body)
  }

  @Tool(description = "Shows a location on the map.")
  fun showLocationOnMap(
    @ToolParam(
      description =
        "The location to search for. May be the name of a place, a business, or an address."
    )
    location: String
  ): Map<String, String> {
    Log.d(TAG, "Show location on map. Location: '$location'")
    onFunctionCalled(ShowLocationOnMap(location))
    return success("location" to location)
  }

  @Tool(description = "Opens the WiFi settings.")
  fun openWifiSettings(): Map<String, String> {
    Log.d(TAG, "Open wifi settings")
    onFunctionCalled(OpenWifiSettingsAction())
    return success()
  }

  @Tool(description = "Launches/opens an installed app on the phone by its name.")
  fun launchApp(
    @ToolParam(description = "The name of the app to open, e.g. \"YouTube\", \"Settings\", \"Maps\".")
    appName: String
  ): Map<String, String> {
    Log.d(TAG, "Launch app: '$appName'")
    onFunctionCalled(LaunchAppAction(appName))
    return success("app" to appName)
  }

  @Tool(description = "Creates a new calendar event.")
  fun createCalendarEvent(
    @ToolParam(description = "The date and time of the event in the format YYYY-MM-DDTHH:MM:SS.")
    datetime: String,
    @ToolParam(description = "The title of the event.") title: String,
  ): Map<String, String> {
    Log.d(TAG, "Create calendar event. Datetime: '$datetime', title: '$title'")
    onFunctionCalled(CreateCalendarEventAction(datetime, title))
    return success("datetime" to datetime, "title" to title)
  }

  private fun success(vararg extras: Pair<String, String>): Map<String, String> =
    mapOf("result" to "success", *extras)
}
