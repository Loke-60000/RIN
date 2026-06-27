// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat.tools

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

private const val TAG = "AGChatSkillToolSet"

/**
 * Exposes skill loading to on-device models. Mirrors the agentchat `loadSkill` dispatcher (same name
 * and `Map<String, String>` result shape).
 *
 * `runJs` / `runIntent` are intentionally not ported here: `runJs` executes in a WebView driven by
 * the agentchat UI action channel, and `runIntent` duplicates the chat package's existing
 * `MobileActionsTools`. Both belong to the later UI-wiring step.
 */
class SkillToolSet(private val skillManager: SkillManager) : ToolSet {
  @Tool(description = "Loads a skill.")
  fun loadSkill(
    @ToolParam(description = "The name of the skill to load.") skillName: String
  ): Map<String, String> {
    val skillContent = skillManager.loadSkill(skillName) ?: "Skill not found"
    Log.d(TAG, "load skill \"$skillName\". Content:\n$skillContent")
    return mapOf("skill_name" to skillName, "skill_instructions" to skillContent)
  }
}
