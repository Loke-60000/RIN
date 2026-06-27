// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AGClipEmbedder"
private const val IMAGE_SIZE = 256 // MobileCLIP-S0 input resolution
private const val BASE = "https://huggingface.co/Xenova/mobileclip_s0/resolve/main"

/**
 * MobileCLIP-S0 multimodal embedder (text + image → shared 512-d space) on ONNX Runtime — a tiny,
 * GMS-free model (~55MB int8) so it works on GrapheneOS. The model is downloaded on first use to
 * keep the APK small; until [prepare] finishes, [isReady] is false and callers fall back to lexical.
 */
@Singleton
class MobileClipEmbedder @Inject constructor(@ApplicationContext private val context: Context) :
  Embedder {

  private val dir = File(context.filesDir, "clip").apply { mkdirs() }
  // fp16 (not int8): ONNX Runtime can't execute the int8 export's ConvInteger ops on this device.
  private val visionFile = File(dir, "vision_model_fp16.onnx")
  private val textFile = File(dir, "text_model_fp16.onnx")
  private val tokenizerFile = File(dir, "tokenizer.json")

  private var env: OrtEnvironment? = null
  private var visionSession: OrtSession? = null
  private var textSession: OrtSession? = null
  private var tokenizer: ClipBpeTokenizer? = null
  @Volatile private var ready = false
  @Volatile private var loading = false

  override val dim: Int
    get() = if (ready) 512 else 0

  override fun isReady(): Boolean = ready

  fun isDownloaded(): Boolean =
    visionFile.exists() && textFile.exists() && tokenizerFile.exists()

  /** Total bytes the embedder occupies on disk. */
  fun sizeBytes(): Long = dir.walk().filter { it.isFile }.sumOf { it.length() }

  /** Unloads and removes the downloaded model from disk. */
  @Synchronized
  fun delete() {
    runCatching { visionSession?.close() }
    runCatching { textSession?.close() }
    visionSession = null
    textSession = null
    tokenizer = null
    ready = false
    dir.deleteRecursively()
  }

  /** Downloads (if needed) and loads the model. Blocking — call from a background dispatcher. */
  @Synchronized
  fun prepare() {
    if (ready || loading) return
    loading = true
    try {
      if (!isDownloaded()) {
        Log.i(TAG, "Downloading MobileCLIP model…")
        download("$BASE/onnx/vision_model_fp16.onnx", visionFile)
        download("$BASE/onnx/text_model_fp16.onnx", textFile)
        download("$BASE/tokenizer.json", tokenizerFile)
      }
      val e = OrtEnvironment.getEnvironment()
      val opts = OrtSession.SessionOptions()
      visionSession = e.createSession(visionFile.absolutePath, opts)
      textSession = e.createSession(textFile.absolutePath, opts)
      tokenizer = ClipBpeTokenizer.fromFile(tokenizerFile)
      env = e
      ready = true
      Log.i(TAG, "MobileCLIP embedder ready (dim=512)")
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to prepare embedder", t)
      ready = false
    } finally {
      loading = false
    }
  }

  override fun embedText(text: String): FloatArray? {
    val e = env ?: return null
    val session = textSession ?: return null
    val tok = tokenizer ?: return null
    return try {
      val ids = tok.encode(text)
      val idsLong = LongArray(ids.size) { ids[it].toLong() }
      var lastReal = 0
      for (i in ids.indices) if (ids[i] != 0) lastReal = i
      val mask = LongArray(ids.size) { if (it <= lastReal) 1L else 0L }
      val shape = longArrayOf(1, ids.size.toLong())
      val inputs = HashMap<String, OnnxTensor>()
      for (name in session.inputNames) {
        when {
          name.contains("mask", ignoreCase = true) ->
            inputs[name] = OnnxTensor.createTensor(e, LongBuffer.wrap(mask), shape)
          name.contains("id", ignoreCase = true) ->
            inputs[name] = OnnxTensor.createTensor(e, LongBuffer.wrap(idsLong), shape)
        }
      }
      val vec = runAndExtract(session, inputs)
      vec?.let { l2Normalize(it) }
    } catch (t: Throwable) {
      Log.e(TAG, "embedText failed", t)
      null
    }
  }

  override fun embedImage(image: ByteArray): FloatArray? {
    val e = env ?: return null
    val session = visionSession ?: return null
    return try {
      val bmp = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return null
      val pixels = preprocess(bmp)
      val shape = longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
      val inputName = session.inputNames.firstOrNull() ?: return null
      val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(pixels), shape)
      val vec = runAndExtract(session, mapOf(inputName to tensor))
      vec?.let { l2Normalize(it) }
    } catch (t: Throwable) {
      Log.e(TAG, "embedImage failed", t)
      null
    }
  }

  private fun runAndExtract(session: OrtSession, inputs: Map<String, OnnxTensor>): FloatArray? {
    val result = session.run(inputs)
    return try {
      // Prefer an explicit embedding output; otherwise take the first.
      val entry =
        result.firstOrNull { it.key.contains("embed", ignoreCase = true) } ?: result.firstOrNull()
      val tensor = entry?.value as? OnnxTensor ?: return null
      val fb = tensor.floatBuffer ?: return null
      FloatArray(fb.remaining()).also { fb.get(it) }
    } finally {
      result.close()
      inputs.values.forEach { it.close() }
    }
  }

  /** Resize so the shortest edge is [IMAGE_SIZE], center-crop, then NCHW float32 scaled to [0,1]. */
  private fun preprocess(src: Bitmap): FloatArray {
    val scale = IMAGE_SIZE.toFloat() / minOf(src.width, src.height)
    val sw = Math.round(src.width * scale)
    val sh = Math.round(src.height * scale)
    val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
    val left = (sw - IMAGE_SIZE) / 2
    val top = (sh - IMAGE_SIZE) / 2
    val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
    scaled.getPixels(pixels, 0, IMAGE_SIZE, left, top, IMAGE_SIZE, IMAGE_SIZE)
    val area = IMAGE_SIZE * IMAGE_SIZE
    val out = FloatArray(3 * area)
    for (i in 0 until area) {
      val p = pixels[i]
      out[i] = ((p shr 16) and 0xFF) / 255f // R plane
      out[area + i] = ((p shr 8) and 0xFF) / 255f // G plane
      out[2 * area + i] = (p and 0xFF) / 255f // B plane
    }
    return out
  }

  private fun download(url: String, dest: File) {
    val tmp = File(dest.absolutePath + ".part")
    URL(url).openStream().use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
    if (!tmp.renameTo(dest)) {
      tmp.copyTo(dest, overwrite = true)
      tmp.delete()
    }
  }
}
