// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

enum class ChatRole {
  USER,
  ASSISTANT,
}

data class ChatMessage(
  val role: ChatRole,
  val text: String,
  val ts: Long = System.currentTimeMillis(),
  /** Absolute file paths of any images attached to this (user) message. */
  val imagePaths: List<String> = emptyList(),
  /** Absolute file path of a voice clip (WAV) attached to this (user) message, shown as a bubble. */
  val audioPath: String? = null,
  /** The model's reasoning/"thinking" for an assistant message, if any. */
  val thinking: String = "",
  /** Web-search sources used to answer (assistant message), shown as clickable pills. */
  val sources: List<SearchResult> = emptyList(),
)

/** A single saved conversation. */
data class Conversation(
  val id: String,
  var title: String,
  val createdAt: Long,
  var updatedAt: Long,
  val messages: MutableList<ChatMessage> = mutableListOf(),
)
