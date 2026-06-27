// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat.tools

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "AGChatMcpToolSet"

/**
 * Exposes connected MCP tools to on-device models as a single native function call. Mirrors the
 * agentchat `runMcpTool` dispatcher (same name and `Map<String, String>` result shape), but routes
 * permission through [PermissionGate] instead of the UI action channel.
 */
class McpToolSet(
  private val mcpManager: McpManager,
  private val permissionGate: PermissionGate,
) : ToolSet {
  @Tool(description = "Run a MCP tool")
  fun runMcpTool(
    @ToolParam(description = "The name of the tool to run.") toolName: String,
    @ToolParam(description = "The parameters passed to tool as input") input: String,
  ): Map<String, String> {
    Log.d(TAG, "Run MCP tool:\n- name: $toolName\n- input: $input")
    return runBlocking(Dispatchers.IO) {
      if (!mcpManager.isToolAlwaysAllow(toolName)) {
        val allowed = permissionGate.confirmMcpToolCall(toolName = toolName, argument = input)
        if (!allowed) {
          return@runBlocking mapOf("error" to "Permission denied by user", "status" to "failed")
        }
      }
      when (val result = mcpManager.callTool(toolName, input)) {
        is McpCallResult.Success -> mapOf("result" to result.text, "status" to "succeeded")
        is McpCallResult.Failure -> mapOf("error" to result.error, "status" to "failed")
      }
    }
  }
}
