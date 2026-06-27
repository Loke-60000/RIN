// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat.tools

import android.util.Log

/**
 * Decides whether an MCP tool call may proceed. This is the seam the UI step will replace with a
 * real dialog: today the engine runs without any tool-call UI, so the default implementation always
 * allows (and logs) rather than blocking on a confirmation that nothing would ever surface.
 */
fun interface PermissionGate {
  /** Returns true to allow the call, false to deny it. Must not block on UI in the default impl. */
  suspend fun confirmMcpToolCall(toolName: String, argument: String): Boolean

  companion object {
    /** Allow-everything gate used until the confirmation UI is wired up. */
    val ALLOW = PermissionGate { toolName, _ ->
      Log.d("AGPermissionGate", "Auto-allowing MCP tool call \"$toolName\" (no UI gate wired yet)")
      true
    }
  }
}
