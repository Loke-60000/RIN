// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa

import android.os.Bundle

/** Drop-in analytics sink that discards every event. Stands in for the removed Firebase backend. */
class NoOpAnalytics {
  @Suppress("UNUSED_PARAMETER")
  fun logEvent(name: String, params: Bundle?) {
    // No telemetry is collected.
  }
}

/**
 * Kept `null` on purpose so legacy `firebaseAnalytics?.logEvent(...)` calls stay source-compatible
 * and quietly do nothing.
 */
val firebaseAnalytics: NoOpAnalytics?
  get() = null

/** Event identifiers referenced by call sites; only the string [id]s are externally meaningful. */
enum class GalleryEvent(val id: String) {
  CAPABILITY_SELECT("capability_select"),
  MODEL_DOWNLOAD("model_download"),
  GENERATE_ACTION("generate_action"),
  BUTTON_CLICKED("button_clicked"),
  SKILL_MANAGEMENT("skill_management"),
  SKILL_EXECUTION("skill_execution"),
  CHAT_HISTORY("chat_history"),
  MCP_MANAGEMENT("mcp_management"),
  MCP_EXECUTION("mcp_execution"),
}
