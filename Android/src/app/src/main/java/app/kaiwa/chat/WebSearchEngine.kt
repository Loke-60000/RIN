// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.URLEncoder

private const val TAG = "AGWebSearch"

data class SearchResult(val title: String, val url: String, val snippet: String)

/** Native web search across DuckDuckGo (HTML), SearXNG (JSON), and the Ollama web-search API. */
class WebSearchEngine(private val prefs: ChatPrefs) {
  private val gson = Gson()
  private val client = HttpClient(Android)

  val provider: String
    get() = prefs.webSearchProvider

  val enabled: Boolean
    get() = provider != "NONE"

  suspend fun search(query: String, limit: Int = 5): List<SearchResult> {
    return try {
      when (provider) {
        "SEARXNG" -> searxng(query, limit)
        "OLLAMA" -> ollama(query, limit)
        "DUCKDUCKGO" -> duckduckgo(query, limit)
        else -> emptyList()
      }
    } catch (e: Exception) {
      Log.e(TAG, "search failed ($provider)", e)
      emptyList()
    }
  }

  /** Formats search results into a context block to prepend to the model prompt. */
  suspend fun contextFor(query: String, limit: Int = 5): String {
    val results = search(query, limit)
    if (results.isEmpty()) return ""
    val body =
      results.joinToString("\n\n") { "• ${it.title}\n${it.url}\n${it.snippet}" }
    return "Web search results for \"$query\":\n$body\n\nUse these results and cite the sources."
  }

  private suspend fun searxng(query: String, limit: Int): List<SearchResult> {
    val base = prefs.searxngUrl.trimEnd('/')
    if (base.isBlank()) return emptyList()
    val url = "$base/search?q=${enc(query)}&format=json"
    val text = client.get(url) { header("Accept", "application/json") }.bodyAsText()
    val arr = JsonParser.parseString(text).asJsonObject.getAsJsonArray("results") ?: return emptyList()
    return arr.take(limit).map {
      val o = it.asJsonObject
      SearchResult(
        title = o.get("title")?.asString ?: "",
        url = o.get("url")?.asString ?: "",
        snippet = o.get("content")?.asString ?: "",
      )
    }
  }

  private suspend fun ollama(query: String, limit: Int): List<SearchResult> {
    // Reuse the saved Ollama provider key so the user never re-types it just for web search.
    val key = prefs.ollamaSearchKey.ifBlank { prefs.ollamaProviderKey() }
    if (key.isBlank()) return emptyList()
    val text =
      client
        .post("https://ollama.com/api/web_search") {
          contentType(ContentType.Application.Json)
          header("Authorization", "Bearer $key")
          setBody(gson.toJson(mapOf("query" to query, "max_results" to limit)))
        }
        .bodyAsText()
    val arr = JsonParser.parseString(text).asJsonObject.getAsJsonArray("results") ?: return emptyList()
    return arr.take(limit).map {
      val o = it.asJsonObject
      SearchResult(
        title = o.get("title")?.asString ?: "",
        url = o.get("url")?.asString ?: "",
        snippet = o.get("content")?.asString ?: o.get("snippet")?.asString ?: "",
      )
    }
  }

  private suspend fun duckduckgo(query: String, limit: Int): List<SearchResult> {
    val html =
      client
        .get("https://html.duckduckgo.com/html/?q=${enc(query)}") {
          header("User-Agent", "Mozilla/5.0 (Android) OkGemma")
        }
        .bodyAsText()
    val results = mutableListOf<SearchResult>()
    // <a class="result__a" href="URL">TITLE</a> ... <a class="result__snippet">SNIPPET</a>
    val linkRe = Regex("result__a\"[^>]*href=\"(.*?)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    val snippetRe = Regex("result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    val links = linkRe.findAll(html).toList()
    val snippets = snippetRe.findAll(html).toList()
    for (i in links.indices) {
      if (results.size >= limit) break
      val href = normalizeDdgUrl(links[i].groupValues[1])
      val title = stripHtml(links[i].groupValues[2])
      val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.let { stripHtml(it) } ?: ""
      if (href.isNotBlank() && title.isNotBlank()) results.add(SearchResult(title, href, snippet))
    }
    return results
  }

  private fun normalizeDdgUrl(raw: String): String {
    var u = raw
    if (u.startsWith("//")) u = "https:$u"
    val marker = "uddg="
    val idx = u.indexOf(marker)
    if (idx >= 0) {
      val enc = u.substring(idx + marker.length).substringBefore("&")
      return try {
        java.net.URLDecoder.decode(enc, "UTF-8")
      } catch (e: Exception) {
        u
      }
    }
    return u
  }

  private fun stripHtml(s: String): String =
    s.replace(Regex("<.*?>"), "").replace("&amp;", "&").replace("&#x27;", "'").trim()

  private fun enc(q: String) = URLEncoder.encode(q, "UTF-8")
}
