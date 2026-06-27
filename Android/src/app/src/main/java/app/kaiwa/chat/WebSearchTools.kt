// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.runBlocking

/**
 * Exposes web search to on-device models as a native function call, so the model searches the web
 * only when it decides it needs current/external facts — not on every message. Captured results are
 * reported via [onResults] so the chat can render source pills.
 */
class WebSearchTools(
  private val webSearch: WebSearchEngine,
  private val onResults: (List<SearchResult>) -> Unit,
) : ToolSet {
  @Tool(
    description =
      "Searches the web for current events or facts you don't know. Only call this when the " +
        "answer needs up-to-date or external information."
  )
  fun searchWeb(
    @ToolParam(description = "The search query.") query: String
  ): Map<String, String> {
    if (!webSearch.enabled) return mapOf("error" to "web search is not configured")
    val results = runBlocking { webSearch.search(query) }
    onResults(results)
    if (results.isEmpty()) return mapOf("result" to "no results found")
    return mapOf(
      "result" to results.joinToString("\n\n") { "• ${it.title}\n${it.url}\n${it.snippet}" }
    )
  }
}
