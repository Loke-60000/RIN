// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import app.kaiwa.chat.ChatMessage

/**
 * Process-level hand-off of the assistant popup's transcript to the main app. The popup chat is
 * ephemeral and never saved on its own; it's only published here when the user taps "Open Rin"
 * to continue it. The chat screen consumes (and clears) it on next launch.
 */
object PopupHandoff {
  @Volatile private var pending: List<ChatMessage>? = null

  fun publish(messages: List<ChatMessage>) {
    pending = if (messages.isEmpty()) null else messages.toList()
  }

  /** Returns the pending transcript (if any) and clears it so it's adopted only once. */
  fun consume(): List<ChatMessage>? {
    val m = pending
    pending = null
    return m
  }
}
