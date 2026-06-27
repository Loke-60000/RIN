// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

private const val TAG = "AGRagStore"

/** Minimum cosine similarity for a chunk to count as a semantic match. */
private const val SEMANTIC_FLOOR = 0.2

data class RagChunk(
  val id: String,
  val docId: String,
  val title: String,
  val text: String,
  /** L2-normalised embedding when an [Embedder] was available at ingest; null = lexical-only. */
  val embedding: List<Float>? = null,
)

data class RagDoc(val id: String, val title: String, val chunks: Int)

/**
 * Lightweight RAG store: documents are split into chunks persisted to disk. When an [Embedder] is
 * supplied, chunks are embedded at ingest and retrieved by cosine similarity (semantic); otherwise
 * — and as a fallback — it uses lexical term-overlap scoring, so it still works with no model loaded.
 */
class RagStore(context: Context, private val embedder: Embedder? = null) {
  private val gson = Gson()
  private val file = File(context.filesDir, "rag_chunks.json")
  private val stopwords =
    setOf(
      "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "was", "were", "for", "on",
      "with", "as", "by", "at", "it", "this", "that", "be", "from", "you", "i", "we", "they",
    )

  private fun load(): MutableList<RagChunk> {
    if (!file.exists()) return mutableListOf()
    return try {
      gson.fromJson(file.readText(), object : TypeToken<MutableList<RagChunk>>() {}.type)
    } catch (e: Exception) {
      Log.e(TAG, "load failed", e)
      mutableListOf()
    }
  }

  private fun save(chunks: List<RagChunk>) {
    try {
      file.writeText(gson.toJson(chunks))
    } catch (e: Exception) {
      Log.e(TAG, "save failed", e)
    }
  }

  fun addDocument(title: String, text: String) {
    val docId = UUID.randomUUID().toString()
    val pieces = chunk(text)
    val chunks = load()
    val canEmbed = embedder?.isReady() == true
    pieces.forEach { piece ->
      val emb = if (canEmbed) embedder?.embedText(piece)?.toList() else null
      chunks.add(RagChunk(UUID.randomUUID().toString(), docId, title, piece, emb))
    }
    save(chunks)
  }

  fun listDocuments(): List<RagDoc> =
    load().groupBy { it.docId }.map { (id, cs) -> RagDoc(id, cs.first().title, cs.size) }

  fun deleteDocument(docId: String) = save(load().filter { it.docId != docId })

  fun clear() = save(emptyList())

  /** Returns a context block of the most relevant chunks for [query], or "" if nothing matches. */
  fun retrieve(query: String, k: Int = 3): String {
    val all = load()
    if (all.isEmpty()) return ""
    // Semantic retrieval when an embedder is ready; fall back to lexical if it (or matches) are absent.
    val qVec = if (embedder?.isReady() == true) embedder.embedText(query) else null
    val scored =
      if (qVec != null) {
        all
          .mapNotNull { c -> c.embedding?.let { c to cosineSimilarity(qVec, it.toFloatArray()).toDouble() } }
          .filter { it.second > SEMANTIC_FLOOR }
          .sortedByDescending { it.second }
          .take(k)
          .ifEmpty { lexical(query, all, k) }
      } else {
        lexical(query, all, k)
      }
    if (scored.isEmpty()) return ""
    val body = scored.joinToString("\n\n") { "From \"${it.first.title}\":\n${it.first.text}" }
    return "Relevant notes from the user's documents:\n$body\n\nUse them if helpful."
  }

  private fun lexical(query: String, all: List<RagChunk>, k: Int): List<Pair<RagChunk, Double>> {
    val qTerms = tokenize(query).toSet()
    if (qTerms.isEmpty()) return emptyList()
    return all
      .map { it to score(qTerms, it.text) }
      .filter { it.second > 0.0 }
      .sortedByDescending { it.second }
      .take(k)
  }

  private fun score(qTerms: Set<String>, text: String): Double {
    val tokens = tokenize(text)
    if (tokens.isEmpty()) return 0.0
    val hits = tokens.count { it in qTerms }
    return hits / kotlin.math.sqrt(tokens.size.toDouble())
  }

  private fun tokenize(s: String): List<String> =
    s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in stopwords }

  private fun chunk(text: String, size: Int = 600): List<String> {
    val clean = text.trim()
    if (clean.length <= size) return if (clean.isEmpty()) emptyList() else listOf(clean)
    val out = mutableListOf<String>()
    var i = 0
    while (i < clean.length) {
      val end = minOf(i + size, clean.length)
      out.add(clean.substring(i, end))
      i = end
    }
    return out
  }
}
