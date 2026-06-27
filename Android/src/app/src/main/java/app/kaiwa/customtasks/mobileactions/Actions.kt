// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.customtasks.mobileactions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/** Every device action the assistant is able to recognize and execute. */
enum class ActionType {
  ACTION_FLASHLIGHT_ON,
  ACTION_FLASHLIGHT_OFF,
  ACTION_CREATE_CONTACT,
  ACTION_SEND_EMAIL,
  ACTION_SHOW_LOCATION_ON_MAP,
  ACTION_OPEN_WIFI_SETTINGS,
  ACTION_CREATE_CALENDAR_EVENT,
  ACTION_LAUNCH_APP,
}

/** Record of the tool the model invoked, surfaced in the chat transcript. */
data class FunctionCallDetails(
  val functionName: String,
  val parameters: List<Pair<String, String>>,
  val ts: Long = System.currentTimeMillis(),
)

/**
 * A device action the model asked for.
 *
 * @property type discriminator used when dispatching the action
 * @property icon glyph shown alongside the model's reply
 * @property functionCallDetails description of the originating tool call
 */
abstract class Action(
  val type: ActionType,
  val icon: ImageVector,
  val functionCallDetails: FunctionCallDetails,
)

class FlashlightOnAction :
  Action(
    type = ActionType.ACTION_FLASHLIGHT_ON,
    icon = Icons.Outlined.FlashlightOn,
    functionCallDetails = FunctionCallDetails("turnOnFlashlight", emptyList()),
  )

class FlashlightOffAction :
  Action(
    type = ActionType.ACTION_FLASHLIGHT_OFF,
    icon = Icons.Outlined.FlashOff,
    functionCallDetails = FunctionCallDetails("turnOffFlashlight", emptyList()),
  )

class CreateContactAction(
  val firstName: String,
  val lastName: String,
  val phoneNumber: String,
  val email: String,
) :
  Action(
    type = ActionType.ACTION_CREATE_CONTACT,
    icon = Icons.Outlined.PersonAdd,
    functionCallDetails =
      FunctionCallDetails(
        functionName = "createContact",
        parameters =
          listOf(
            "firstName" to firstName,
            "lastName" to lastName,
            "phoneNumber" to phoneNumber,
            "email" to email,
          ),
      ),
  )

class SendEmailAction(val to: String, val subject: String, val body: String) :
  Action(
    type = ActionType.ACTION_SEND_EMAIL,
    icon = Icons.Outlined.Email,
    functionCallDetails =
      FunctionCallDetails(
        functionName = "sendEmail",
        parameters = listOf("to" to to, "subject" to subject, "body" to body),
      ),
  )

class ShowLocationOnMap(val location: String) :
  Action(
    type = ActionType.ACTION_SHOW_LOCATION_ON_MAP,
    icon = Icons.Outlined.Map,
    functionCallDetails =
      FunctionCallDetails(functionName = "showLocationOnMap", parameters = listOf("location" to location)),
  )

class OpenWifiSettingsAction :
  Action(
    type = ActionType.ACTION_OPEN_WIFI_SETTINGS,
    icon = Icons.Outlined.Wifi,
    functionCallDetails = FunctionCallDetails("openWifiSettings", emptyList()),
  )

class LaunchAppAction(val appName: String) :
  Action(
    type = ActionType.ACTION_LAUNCH_APP,
    icon = Icons.Outlined.Launch,
    functionCallDetails =
      FunctionCallDetails(functionName = "launchApp", parameters = listOf("appName" to appName)),
  )

class CreateCalendarEventAction(val datetime: String, val title: String) :
  Action(
    type = ActionType.ACTION_CREATE_CALENDAR_EVENT,
    icon = Icons.Outlined.CalendarMonth,
    functionCallDetails =
      FunctionCallDetails(
        functionName = "createCalendarEvent",
        parameters = listOf("datetime" to datetime, "title" to title),
      ),
  )
