// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

import androidx.annotation.StringRes
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import app.kaiwa.R

/**
 * A single entry on the home screen.
 *
 * Tasks are bucketed by [category] into the home-screen tabs (the tab strip disappears when there
 * is only one bucket). Selecting a task reveals its [models].
 */
data class Task(
  /** Stable identifier. Values declared in [BuiltInTaskId] are reserved. */
  val id: String,

  /** Human-readable title. */
  val label: String,

  /** Bucket this task belongs to; drives the home-screen tabs. */
  val category: CategoryInfo,

  /** Tile icon. Ignored when [iconVectorResourceId] is provided. */
  val icon: ImageVector? = null,

  /** Vector drawable resource for the tile icon. Wins over [icon] when both are set. */
  val iconVectorResourceId: Int? = null,

  /** Long-form copy rendered atop the task screen. */
  val description: String,

  /** Brief (~6 word) blurb. */
  val shortDescription: String = "",

  /** Optional docs link rendered under the description. */
  val docUrl: String = "",

  /** Optional source-code link rendered under the description. */
  val sourceCodeUrl: String = "",

  /** Models offered by this task. */
  val models: MutableList<Model>,

  /** Allowlist model names to resolve into [models] when non-empty. */
  val modelNames: List<String> = listOf(),

  /** When true, the task handles config edits itself instead of auto-reinitializing the model. */
  val handleModelConfigChangesInTask: Boolean = false,

  /** Flags the task as experimental. */
  val experimental: Boolean = false,

  /** Shows a "new" badge on the home screen. */
  val newFeature: Boolean = false,

  /** Use the theme color in place of the task tint. */
  val useThemeColor: Boolean = false,

  /** Default system prompt applied for this task. */
  val defaultSystemPrompt: String = "",

  /** Fallback agent name shown above chat messages. */
  @StringRes val agentNameRes: Int = R.string.chat_generic_agent_name,

  /** Hint text for the chat input field. */
  @StringRes val textInputPlaceHolderRes: Int = R.string.chat_textinput_placeholder,

  // App-managed; callers normally leave these alone.
  var index: Int = -1,
  val updateTrigger: MutableState<Long> = mutableLongStateOf(0),
) {
  /** True when [model] enables [capability] for this task. */
  fun allowCapability(capability: ModelCapability, model: Model): Boolean =
    model.capabilityToTaskTypes[capability]?.contains(id) == true
}

/** Reserved task identifiers for the app's built-in tasks. */
object BuiltInTaskId {
  const val LLM_CHAT = "llm_chat"
  const val LLM_PROMPT_LAB = "llm_prompt_lab"
  const val LLM_ASK_IMAGE = "llm_ask_image"
  const val LLM_ASK_AUDIO = "llm_ask_audio"
  const val LLM_MOBILE_ACTIONS = "llm_mobile_actions"
  const val MP_SCRAPBOOK = "mp_scrapbook"
  const val LLM_AGENT_CHAT = "llm_agent_chat"
}

private val LEGACY_TASK_IDS: Set<String> =
  setOf(
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_AGENT_CHAT,
  )

/** Whether [id] denotes one of the retired/legacy tasks. */
fun isLegacyTasks(id: String): Boolean = id in LEGACY_TASK_IDS
