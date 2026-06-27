// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.CompletableDeferred

/** Anything that can report an inference latency in milliseconds. */
interface LatencyProvider {
  val latencyMs: Float
}

data class Classification(val label: String, val score: Float, val color: Color)

data class JsonObjAndTextContent<T>(val jsonObj: T, val textContent: String)

class AudioClip(val audioData: ByteArray, val sampleRate: Int)

/** Identifiers for the agent actions surfaced to the UI layer. */
enum class AgentActionName {
  CALL_JS_SKILL,
  SKILL_PROGRESS,
  ASK_INFO,
  REQUEST_PERMISSION,
  ASK_MCP_TOOL_CALL_PERMISSION,
}

/** Base class for an action an agent asks the host UI to carry out. */
open class AgentAction(val name: AgentActionName)

class CallJsAgentAction(
  val url: String,
  val data: String,
  val secret: String = "",
  val result: CompletableDeferred<String> = CompletableDeferred(),
) : AgentAction(AgentActionName.CALL_JS_SKILL)

class AskInfoAgentAction(
  val dialogTitle: String,
  val fieldLabel: String,
  val result: CompletableDeferred<String> = CompletableDeferred(),
) : AgentAction(AgentActionName.ASK_INFO)

class SkillProgressAgentAction(
  val label: String,
  val inProgress: Boolean,
  val addItemTitle: String = "",
  val addItemDescription: String = "",
  val customData: Any? = null,
) : AgentAction(AgentActionName.SKILL_PROGRESS)

/** Requests an Android runtime permission (e.g. calendar read) before proceeding. */
class RequestPermissionAgentAction(
  val permission: String,
  val result: CompletableDeferred<Boolean> = CompletableDeferred(),
) : AgentAction(AgentActionName.REQUEST_PERMISSION)

/** Outcome of an [AskMcpToolCallPermissionAction] prompt. */
enum class PermissionResult {
  DENY,
  ALLOW_ONCE,
  ALWAYS_ALLOW,
}

/** Asks the user to authorize a particular MCP tool invocation. */
class AskMcpToolCallPermissionAction(
  val toolName: String,
  val argument: String,
  val result: CompletableDeferred<PermissionResult> = CompletableDeferred(),
) : AgentAction(AgentActionName.ASK_MCP_TOOL_CALL_PERMISSION)

data class SkillTryOutChip(
  val icon: ImageVector,
  val label: String,
  val prompt: String,
  val skillName: String,
)

data class SkillInfo(
  val skillMd: String,
  val skillUrl: String? = null,
  val tryoutChip: SkillTryOutChip? = null,
)

data class SkillsIndex(val skills: List<SkillInfo>)

@JsonClass(generateAdapter = true)
data class CallJsSkillResult(
  val result: String?,
  val error: String?,
  val image: CallJsSkillResultImage?,
  val webview: CallJsSkillResultWebview?,
)

@JsonClass(generateAdapter = true) data class CallJsSkillResultImage(val base64: String?)

@JsonClass(generateAdapter = true)
data class CallJsSkillResultWebview(
  val url: String?,
  val iframe: Boolean?,
  // The webview spans the full screen width; this ratio derives its height. Defaults to 4:3.
  val aspectRatio: Float?,
)
