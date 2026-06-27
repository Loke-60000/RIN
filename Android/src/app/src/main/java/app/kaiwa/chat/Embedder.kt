// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import kotlin.math.sqrt

/**
 * A lightweight on-device multimodal embedder: maps text and images into one shared vector space so
 * a text query can retrieve both relevant notes and images by cosine similarity. Implementations
 * load a small model lazily and must run GMS-free (no Play Services) so they work on GrapheneOS.
 *
 * Vectors are L2-normalised, so cosine similarity is a plain dot product.
 */
interface Embedder {
  /** Dimension of the produced vectors, or 0 if the model isn't loaded yet. */
  val dim: Int

  /** True once the model is loaded and [embedText]/[embedImage] can return real vectors. */
  fun isReady(): Boolean

  /** Embeds [text]; null if the model isn't ready or embedding failed. */
  fun embedText(text: String): FloatArray?

  /** Embeds a decoded image; null if images aren't supported or embedding failed. */
  fun embedImage(image: ByteArray): FloatArray?
}

/** Cosine similarity of two vectors. Cheap dot product when both are already L2-normalised. */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
  if (a.size != b.size || a.isEmpty()) return 0f
  var dot = 0f
  var na = 0f
  var nb = 0f
  for (i in a.indices) {
    dot += a[i] * b[i]
    na += a[i] * a[i]
    nb += b[i] * b[i]
  }
  val denom = sqrt(na) * sqrt(nb)
  return if (denom == 0f) 0f else dot / denom
}

/** Returns an L2-normalised copy of [v] (so later similarity is a plain dot product). */
fun l2Normalize(v: FloatArray): FloatArray {
  var norm = 0f
  for (x in v) norm += x * x
  norm = sqrt(norm)
  if (norm == 0f) return v
  return FloatArray(v.size) { v[it] / norm }
}
