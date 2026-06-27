// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import com.google.gson.Gson
import java.io.File
import java.text.Normalizer

/**
 * A faithful Kotlin port of the open_clip / CLIP byte-level BPE tokenizer, reading the HuggingFace
 * `tokenizer.json` (vocab + merges). Produces a fixed-length [CONTEXT_LENGTH] token id sequence:
 * `<|startoftext|>` … `<|endoftext|>` padded with 0 — exactly what MobileCLIP's text tower expects.
 *
 * No native library needed, so it runs GMS-free on GrapheneOS.
 */
class ClipBpeTokenizer private constructor(
  private val vocab: Map<String, Int>,
  private val ranks: Map<Pair<String, String>, Int>,
) {
  private val byteEncoder = bytesToUnicode()
  private val cache = HashMap<String, String>()

  /** Tokenizes [text] to a [CONTEXT_LENGTH]-long id array (BOS … EOS, zero-padded/truncated). */
  fun encode(text: String): IntArray {
    val cleaned = whitespaceClean(text).lowercase()
    val ids = ArrayList<Int>(CONTEXT_LENGTH)
    ids.add(BOS)
    for (match in PAT.findAll(cleaned)) {
      val word = match.value
      val encoded = word.toByteArray(Charsets.UTF_8).joinToString("") { byteEncoder[it.toInt() and 0xFF].toString() }
      for (piece in bpe(encoded).split(" ")) {
        vocab[piece]?.let { ids.add(it) }
      }
    }
    ids.add(EOS)
    val out = IntArray(CONTEXT_LENGTH)
    if (ids.size <= CONTEXT_LENGTH) {
      for (i in ids.indices) out[i] = ids[i]
    } else {
      for (i in 0 until CONTEXT_LENGTH) out[i] = ids[i]
      out[CONTEXT_LENGTH - 1] = EOS // keep the end-of-text marker last after truncation
    }
    return out
  }

  private fun bpe(token: String): String {
    cache[token]?.let { return it }
    if (token.isEmpty()) return token
    val word = ArrayList<String>(token.length)
    for (c in token) word.add(c.toString())
    word[word.size - 1] = word[word.size - 1] + END_OF_WORD
    var pairs = pairsOf(word)
    if (pairs.isEmpty()) {
      val res = token + END_OF_WORD
      cache[token] = res
      return res
    }
    while (true) {
      val bigram = pairs.minByOrNull { ranks[it] ?: Int.MAX_VALUE } ?: break
      if (!ranks.containsKey(bigram)) break
      val (first, second) = bigram
      val newWord = ArrayList<String>(word.size)
      var i = 0
      while (i < word.size) {
        val j = (i until word.size).firstOrNull { word[it] == first } ?: -1
        if (j < 0) {
          for (k in i until word.size) newWord.add(word[k])
          break
        }
        for (k in i until j) newWord.add(word[k])
        i = j
        if (word[i] == first && i < word.size - 1 && word[i + 1] == second) {
          newWord.add(first + second)
          i += 2
        } else {
          newWord.add(word[i])
          i += 1
        }
      }
      word.clear()
      word.addAll(newWord)
      if (word.size == 1) break
      pairs = pairsOf(word)
    }
    val res = word.joinToString(" ")
    cache[token] = res
    return res
  }

  private fun pairsOf(word: List<String>): Set<Pair<String, String>> {
    val pairs = LinkedHashSet<Pair<String, String>>()
    for (i in 0 until word.size - 1) pairs.add(word[i] to word[i + 1])
    return pairs
  }

  companion object {
    const val CONTEXT_LENGTH = 77
    private const val BOS = 49406
    private const val EOS = 49407
    private const val END_OF_WORD = "</w>"

    // The CLIP pre-tokenizer pattern (single digits split individually, per tokenizer.json).
    private val PAT =
      Regex("""'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""")

    private fun whitespaceClean(text: String): String =
      Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim()

    /** GPT-2/CLIP reversible byte→unicode map, so every UTF-8 byte becomes a printable char. */
    private fun bytesToUnicode(): Map<Int, Char> {
      val bs = mutableListOf<Int>()
      (('!'.code)..('~'.code)).forEach { bs.add(it) }
      (('¡'.code)..('¬'.code)).forEach { bs.add(it) }
      (('®'.code)..('ÿ'.code)).forEach { bs.add(it) }
      val cs = bs.toMutableList()
      var n = 0
      for (b in 0..255) {
        if (b !in bs) {
          bs.add(b)
          cs.add(256 + n)
          n += 1
        }
      }
      return bs.indices.associate { bs[it] to cs[it].toChar() }
    }

    /** Builds a tokenizer from a HuggingFace `tokenizer.json` file. */
    fun fromFile(file: File): ClipBpeTokenizer {
      val json = Gson().fromJson(file.readText(), TokenizerJson::class.java)
      val ranks =
        json.model.merges.withIndex().associate { (rank, merge) ->
          val parts = merge.split(" ", limit = 2)
          (parts[0] to parts[1]) to rank
        }
      return ClipBpeTokenizer(json.model.vocab, ranks)
    }
  }

  private class TokenizerJson(val model: Model) {
    class Model(val vocab: Map<String, Int>, val merges: List<String>)
  }
}
