// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

/** Remote chat providers OK Gemma can talk to in addition to the on-device Gemma model. */
enum class ApiProvider(val label: String, val defaultBaseUrl: String, val needsKey: Boolean) {
  OPENAI("OpenAI", "https://api.openai.com/v1", true),
  ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", true),
  GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta", true),
  OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", true),
  OLLAMA("Ollama (local)", "http://localhost:11434/v1", false),
  OLLAMA_CLOUD("Ollama Cloud", "https://ollama.com/v1", true),
  OPENAI_COMPATIBLE("OpenAI-compatible", "", true),
}

/** A saved provider account (key + base URL) that many models can reuse. */
data class ApiCredential(
  val id: String,
  val provider: ApiProvider,
  val name: String,
  val baseUrl: String,
  val apiKey: String,
  /** Auto mode enables every model the endpoint lists; manual mode lets you pick them one by one. */
  val autoEnableModels: Boolean = true,
)

/**
 * A user-configured remote model. OpenAI/OpenRouter/Ollama/Ollama-Cloud/OpenAI-compatible all speak
 * the OpenAI chat-completions wire format; Anthropic and Gemini have their own, handled in
 * [RemoteChatClient]. The baseUrl/apiKey are resolved from a saved [ApiCredential] at save time.
 */
data class ApiModelConfig(
  val id: String,
  val provider: ApiProvider,
  val displayName: String,
  val baseUrl: String,
  val apiKey: String,
  val modelId: String,
  val supportsVision: Boolean = false,
  /** Per-model sampling temperature; null = use the provider/server default. */
  val temperature: Float? = null,
  /** Per-model system prompt; blank = fall back to the app's editable default prompt. */
  val systemPrompt: String = "",
)
