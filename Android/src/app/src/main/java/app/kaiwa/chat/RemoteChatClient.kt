// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "AGRemoteChat"

data class StreamChunk(val text: String = "", val thought: String = "")

/** Streams chat completions from remote providers (OpenAI-style, Anthropic, Gemini). */
class RemoteChatClient {
  private val gson = Gson()
  private val client =
    HttpClient(Android) {
      install(HttpTimeout) {
        requestTimeoutMillis = 120_000
        socketTimeoutMillis = 120_000
        connectTimeoutMillis = 30_000
      }
    }

  fun stream(
    config: ApiModelConfig,
    systemPrompt: String,
    messages: List<ChatMessage>,
    images: List<ByteArray> = emptyList(),
    onSearch: (suspend (String) -> String)? = null,
  ): Flow<StreamChunk> {
    return when (config.provider) {
      ApiProvider.ANTHROPIC -> streamAnthropic(config, systemPrompt, messages, images)
      ApiProvider.GEMINI -> streamGemini(config, systemPrompt, messages, images)
      // Ollama's OpenAI-compat /v1 endpoint has broken vision (Cloud 500s, no response). Route image
      // requests to the native /api/chat with images:[base64]. Text-only keeps the /v1 tool path.
      ApiProvider.OLLAMA, ApiProvider.OLLAMA_CLOUD ->
        if (images.isNotEmpty()) streamOllamaNative(config, systemPrompt, messages, images)
        else streamOpenAi(config, systemPrompt, messages, images, onSearch)
      else -> streamOpenAi(config, systemPrompt, messages, images, onSearch)
    }
  }

  /**
   * Ollama's native `/api/chat`: images are bare base64 (no `data:` prefix) in `messages[].images`,
   * and the response is newline-delimited JSON (not SSE). Used for Ollama vision requests, which the
   * OpenAI-compat layer doesn't handle reliably.
   */
  private fun streamOllamaNative(
    config: ApiModelConfig,
    systemPrompt: String,
    messages: List<ChatMessage>,
    images: List<ByteArray>,
  ): Flow<StreamChunk> = flow {
    val msgs = mutableListOf<Map<String, Any>>()
    if (systemPrompt.isNotBlank()) msgs.add(mapOf("role" to "system", "content" to systemPrompt))
    messages.forEachIndexed { i, m ->
      val role = if (m.role == ChatRole.USER) "user" else "assistant"
      if (i == messages.lastIndex && m.role == ChatRole.USER && images.isNotEmpty()) {
        msgs.add(mapOf("role" to role, "content" to m.text, "images" to images.map { b64(it) }))
      } else {
        msgs.add(mapOf("role" to role, "content" to m.text))
      }
    }
    // baseUrl is the OpenAI-compat URL (…/v1); the native API lives at …/api/chat.
    val root = config.baseUrl.trimEnd('/').removeSuffix("/v1").trimEnd('/')
    val url = "$root/api/chat"
    val body = gson.toJson(mapOf("model" to config.modelId, "messages" to msgs, "stream" to true))
    client
      .preparePost(url) {
        contentType(ContentType.Application.Json)
        headers { if (config.apiKey.isNotBlank()) append("Authorization", "Bearer ${config.apiKey}") }
        setBody(body)
      }
      .execute { response ->
        if (!response.status.isSuccess()) {
          emit(errorChunk(config, response))
          return@execute
        }
        val channel = response.bodyAsChannel()
        while (true) {
          val line = channel.readUTF8Line() ?: break
          if (line.isBlank()) continue
          try {
            val obj = JsonParser.parseString(line).asJsonObject
            obj.get("error")?.let {
              emit(StreamChunk(text = "⚠️ ${it.asString}"))
              return@execute
            }
            val msg = obj.getAsJsonObject("message")
            (msg?.get("content") as? JsonPrimitive)?.asString?.let { if (it.isNotEmpty()) emit(StreamChunk(text = it)) }
            (msg?.get("thinking") as? JsonPrimitive)?.asString?.let { if (it.isNotEmpty()) emit(StreamChunk(thought = it)) }
            if (obj.get("done")?.asJsonPrimitive?.asBoolean == true) break
          } catch (e: Exception) {
            Log.w(TAG, "ollama parse: $line", e)
          }
        }
      }
  }

  companion object {
    /** OpenAI-style function definition for on-demand web search. */
    private val SEARCH_TOOL =
      mapOf(
        "type" to "function",
        "function" to
          mapOf(
            "name" to "search_web",
            "description" to
              "Search the web for current, factual or external information. Call this ONLY when the " +
                "user's request needs up-to-date or external info you don't already know.",
            "parameters" to
              mapOf(
                "type" to "object",
                "properties" to
                  mapOf("query" to mapOf("type" to "string", "description" to "The search query")),
                "required" to listOf("query"),
              ),
          ),
      )
  }

  /**
   * Lists the model ids a provider offers from its models endpoint (OpenAI `/models`, Gemini
   * `/models?key=`, Anthropic `/models`). Returns an empty list on any failure.
   */
  suspend fun listModels(cred: ApiCredential): List<String> =
    try {
      val base = cred.baseUrl.trimEnd('/')
      val url =
        if (cred.provider == ApiProvider.GEMINI) "$base/models?key=${cred.apiKey}" else "$base/models"
      val text =
        client
          .get(url) {
            when (cred.provider) {
              ApiProvider.ANTHROPIC -> {
                header("x-api-key", cred.apiKey)
                header("anthropic-version", "2023-06-01")
              }
              ApiProvider.GEMINI -> {}
              else -> if (cred.apiKey.isNotBlank()) header("Authorization", "Bearer ${cred.apiKey}")
            }
          }
          .bodyAsText()
      val obj = JsonParser.parseString(text).asJsonObject
      // OpenAI/Anthropic: {"data":[{"id":...}]}; Gemini: {"models":[{"name":"models/..."}]}.
      val arr = obj.getAsJsonArray("data") ?: obj.getAsJsonArray("models") ?: return emptyList()
      arr
        .mapNotNull { e ->
          val o = e.asJsonObject
          (o.get("id") ?: o.get("name"))?.asString?.removePrefix("models/")
        }
        .sorted()
    } catch (e: Exception) {
      emptyList()
    }

  /**
   * Ollama (local + Cloud) reports per-model capabilities — e.g. "vision", "tools", "thinking" —
   * from its native `/api/show` endpoint. Used to auto-detect whether a model can see images so the
   * user doesn't have to flip the vision switch by hand. Empty set on any failure.
   */
  suspend fun ollamaCapabilities(cred: ApiCredential, modelId: String): Set<String> =
    try {
      // The OpenAI-compatible base ends in /v1; the native API lives one level up at /api/show.
      val nativeBase = cred.baseUrl.trimEnd('/').removeSuffix("/v1").trimEnd('/')
      val body = JsonObject().apply { addProperty("model", modelId) }.toString()
      val text =
        client
          .post("$nativeBase/api/show") {
            if (cred.apiKey.isNotBlank()) header("Authorization", "Bearer ${cred.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(body)
          }
          .bodyAsText()
      JsonParser.parseString(text)
        .asJsonObject
        .getAsJsonArray("capabilities")
        ?.mapNotNull { it.asString }
        ?.toSet() ?: emptySet()
    } catch (e: Exception) {
      emptySet()
    }

  private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

  /** Reads an error response body (non-2xx) so the failure is shown to the user instead of hanging. */
  private suspend fun errorText(response: HttpResponse): String {
    val ch = response.bodyAsChannel()
    val sb = StringBuilder()
    while (true) {
      val l = ch.readUTF8Line() ?: break
      sb.appendLine(l)
    }
    return sb.toString().trim().take(400)
  }

  /** Builds an actionable error message for a failed request, with a per-status hint. */
  private suspend fun errorChunk(config: ApiModelConfig, response: HttpResponse): StreamChunk {
    val code = response.status.value
    val body = errorText(response)
    val hint =
      when (code) {
        401, 403 -> "Unauthorized — check this model's API key in Settings → API models."
        404 -> "Not found — check the model id \"${config.modelId}\" and the base URL."
        429 -> "Rate limited — too many requests, try again shortly."
        in 500..599 -> "The provider had a server error; try again."
        else -> ""
      }
    return StreamChunk(
      text =
        buildString {
          append("⚠️ ${config.provider.label} error $code")
          if (hint.isNotEmpty()) append("\n$hint")
          if (body.isNotEmpty()) append("\n\n$body")
        }
    )
  }

  // ---- OpenAI-compatible (OpenAI, OpenRouter, Ollama, Ollama Cloud, generic) ----
  private fun streamOpenAi(
    config: ApiModelConfig,
    systemPrompt: String,
    messages: List<ChatMessage>,
    images: List<ByteArray>,
    onSearch: (suspend (String) -> String)? = null,
  ): Flow<StreamChunk> = flow {
    val msgs = mutableListOf<Map<String, Any>>()
    if (systemPrompt.isNotBlank()) msgs.add(mapOf("role" to "system", "content" to systemPrompt))
    messages.forEachIndexed { i, m ->
      val role = if (m.role == ChatRole.USER) "user" else "assistant"
      if (i == messages.lastIndex && m.role == ChatRole.USER && images.isNotEmpty()) {
        val parts = mutableListOf<Map<String, Any>>(mapOf("type" to "text", "text" to m.text))
        images.forEach {
          parts.add(
            mapOf(
              "type" to "image_url",
              "image_url" to mapOf("url" to "data:image/jpeg;base64,${b64(it)}"),
            )
          )
        }
        msgs.add(mapOf("role" to role, "content" to parts))
      } else {
        msgs.add(mapOf("role" to role, "content" to m.text))
      }
    }
    val url = "${config.baseUrl.trimEnd('/')}/chat/completions"
    var iterations = 0
    // Tool loop: offer the web-search function so the model calls it ONLY when it decides it needs
    // current info — instead of every cloud turn pre-fetching the web for no reason.
    while (true) {
      val offerTool = onSearch != null && iterations < 2
      val payload =
        buildMap<String, Any> {
          put("model", config.modelId)
          put("messages", msgs)
          put("stream", true)
          config.temperature?.let { put("temperature", it) }
          if (offerTool) {
            put("tools", listOf(SEARCH_TOOL))
            put("tool_choice", "auto")
          }
        }
      val body = gson.toJson(payload)
      var toolName = ""
      var toolId = ""
      val toolArgs = StringBuilder()
      var finish = ""
      client
        .preparePost(url) {
          contentType(ContentType.Application.Json)
          headers {
            if (config.apiKey.isNotBlank()) append("Authorization", "Bearer ${config.apiKey}")
            if (config.provider == ApiProvider.OPENROUTER) append("HTTP-Referer", "https://okgemma.app")
          }
          setBody(body)
        }
        .execute { response ->
          if (!response.status.isSuccess()) {
            emit(errorChunk(config, response))
            finish = "error"
            return@execute
          }
          val channel = response.bodyAsChannel()
          while (true) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            try {
              val choice =
                JsonParser.parseString(data)
                  .asJsonObject
                  .getAsJsonArray("choices")
                  ?.firstOrNull()
                  ?.asJsonObject
              val delta = choice?.getAsJsonObject("delta")
              // Emit the answer first so nothing below can drop it.
              val contentEl = delta?.get("content")
              if (contentEl != null && contentEl.isJsonPrimitive) {
                val content = contentEl.asString
                if (content.isNotEmpty()) emit(StreamChunk(text = content))
              }
              // Reasoning models stream their thinking in a separate field; type-guarded.
              val reasoningEl = delta?.get("reasoning_content") ?: delta?.get("reasoning")
              if (reasoningEl != null && reasoningEl.isJsonPrimitive) {
                val reasoning = reasoningEl.asString
                if (reasoning.isNotEmpty()) emit(StreamChunk(thought = reasoning))
              }
              // Accumulate a streamed tool call (id + name arrive first; arguments stream in pieces).
              delta?.getAsJsonArray("tool_calls")?.firstOrNull()?.asJsonObject?.let { tc ->
                (tc.get("id") as? JsonPrimitive)?.asString?.let { if (it.isNotBlank()) toolId = it }
                tc.getAsJsonObject("function")?.let { fn ->
                  (fn.get("name") as? JsonPrimitive)?.asString?.let { if (it.isNotBlank()) toolName = it }
                  (fn.get("arguments") as? JsonPrimitive)?.asString?.let { toolArgs.append(it) }
                }
              }
              (choice?.get("finish_reason") as? JsonPrimitive)?.asString?.let { finish = it }
            } catch (e: Exception) {
              Log.w(TAG, "openai parse: $data", e)
            }
          }
        }
      if (finish == "tool_calls" && onSearch != null && toolName == "search_web") {
        val query =
          runCatching { JsonParser.parseString(toolArgs.toString()).asJsonObject.get("query")?.asString }
            .getOrNull()
            .orEmpty()
        val resultsText = if (query.isNotBlank()) onSearch(query) else ""
        msgs.add(
          mapOf(
            "role" to "assistant",
            "content" to "",
            "tool_calls" to
              listOf(
                mapOf(
                  "id" to toolId,
                  "type" to "function",
                  "function" to mapOf("name" to toolName, "arguments" to toolArgs.toString()),
                )
              ),
          )
        )
        msgs.add(
          mapOf("role" to "tool", "tool_call_id" to toolId, "content" to resultsText.ifBlank { "No results found." })
        )
        iterations++
        continue
      }
      break
    }
  }

  // ---- Anthropic ----
  private fun streamAnthropic(
    config: ApiModelConfig,
    systemPrompt: String,
    messages: List<ChatMessage>,
    images: List<ByteArray>,
  ): Flow<StreamChunk> = flow {
    val msgs = mutableListOf<Map<String, Any>>()
    messages.forEachIndexed { i, m ->
      val role = if (m.role == ChatRole.USER) "user" else "assistant"
      if (i == messages.lastIndex && m.role == ChatRole.USER && images.isNotEmpty()) {
        val parts = mutableListOf<Map<String, Any>>()
        images.forEach {
          parts.add(
            mapOf(
              "type" to "image",
              "source" to
                mapOf("type" to "base64", "media_type" to "image/jpeg", "data" to b64(it)),
            )
          )
        }
        parts.add(mapOf("type" to "text", "text" to m.text))
        msgs.add(mapOf("role" to role, "content" to parts))
      } else {
        msgs.add(mapOf("role" to role, "content" to m.text))
      }
    }
    val payload =
      mutableMapOf<String, Any>(
        "model" to config.modelId,
        "messages" to msgs,
        "max_tokens" to 4096,
        "stream" to true,
      )
    if (systemPrompt.isNotBlank()) payload["system"] = systemPrompt
    val url = "${config.baseUrl.trimEnd('/')}/messages"
    client
      .preparePost(url) {
        contentType(ContentType.Application.Json)
        headers {
          append("x-api-key", config.apiKey)
          append("anthropic-version", "2023-06-01")
        }
        setBody(gson.toJson(payload))
      }
      .execute { response ->
        if (!response.status.isSuccess()) {
          emit(errorChunk(config, response))
          return@execute
        }
        val channel = response.bodyAsChannel()
        while (true) {
          val line = channel.readUTF8Line() ?: break
          if (!line.startsWith("data:")) continue
          val data = line.removePrefix("data:").trim()
          try {
            val obj = JsonParser.parseString(data).asJsonObject
            when (obj.get("type")?.asString) {
              "content_block_delta" -> {
                val d = obj.getAsJsonObject("delta")
                d?.get("text")?.takeIf { !it.isJsonNull }?.asString?.let {
                  if (it.isNotEmpty()) emit(StreamChunk(text = it))
                }
                d?.get("thinking")?.takeIf { !it.isJsonNull }?.asString?.let {
                  if (it.isNotEmpty()) emit(StreamChunk(thought = it))
                }
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "anthropic parse: $data", e)
          }
        }
      }
  }

  // ---- Gemini ----
  private fun streamGemini(
    config: ApiModelConfig,
    systemPrompt: String,
    messages: List<ChatMessage>,
    images: List<ByteArray>,
  ): Flow<StreamChunk> = flow {
    val contents = mutableListOf<Map<String, Any>>()
    messages.forEachIndexed { i, m ->
      val role = if (m.role == ChatRole.USER) "user" else "model"
      val parts = mutableListOf<Map<String, Any>>(mapOf("text" to m.text))
      if (i == messages.lastIndex && m.role == ChatRole.USER && images.isNotEmpty()) {
        images.forEach {
          parts.add(mapOf("inlineData" to mapOf("mimeType" to "image/jpeg", "data" to b64(it))))
        }
      }
      contents.add(mapOf("role" to role, "parts" to parts))
    }
    val payload = mutableMapOf<String, Any>("contents" to contents)
    if (systemPrompt.isNotBlank()) {
      payload["systemInstruction"] = mapOf("parts" to listOf(mapOf("text" to systemPrompt)))
    }
    val url =
      "${config.baseUrl.trimEnd('/')}/models/${config.modelId}:streamGenerateContent?alt=sse&key=${config.apiKey}"
    client
      .preparePost(url) {
        contentType(ContentType.Application.Json)
        setBody(gson.toJson(payload))
      }
      .execute { response ->
        if (!response.status.isSuccess()) {
          emit(errorChunk(config, response))
          return@execute
        }
        val channel = response.bodyAsChannel()
        while (true) {
          val line = channel.readUTF8Line() ?: break
          if (!line.startsWith("data:")) continue
          val data = line.removePrefix("data:").trim()
          try {
            val parts =
              JsonParser.parseString(data)
                .asJsonObject
                .getAsJsonArray("candidates")
                ?.firstOrNull()
                ?.asJsonObject
                ?.getAsJsonObject("content")
                ?.getAsJsonArray("parts")
            parts?.forEach { p ->
              val obj = p.asJsonObject
              val textEl = obj.get("text")
              if (textEl == null || !textEl.isJsonPrimitive) return@forEach
              val t = textEl.asString
              if (t.isEmpty()) return@forEach
              // Gemini flags reasoning parts with "thought": true (guard the type so it can't throw).
              val thoughtEl = obj.get("thought")
              val isThought =
                thoughtEl != null &&
                  thoughtEl.isJsonPrimitive &&
                  thoughtEl.asJsonPrimitive.isBoolean &&
                  thoughtEl.asBoolean
              if (isThought) emit(StreamChunk(thought = t)) else emit(StreamChunk(text = t))
            }
          } catch (e: Exception) {
            Log.w(TAG, "gemini parse: $data", e)
          }
        }
      }
  }
}
