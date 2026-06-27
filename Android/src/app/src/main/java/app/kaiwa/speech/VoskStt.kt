// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.speech

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

private const val TAG = "AGVoskStt"
private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
private const val SAMPLE_RATE = 16000.0f

/**
 * Fully on-device, GMS-free speech recognition (Kaldi/Vosk) — a real streaming replacement for the
 * system [android.speech.SpeechRecognizer], which doesn't exist on de-Googled ROMs (GrapheneOS).
 * The ~40MB English model is downloaded on first use; until then [isReady] is false and callers
 * fall back to the system recognizer. Reuses [SttManager.Listener] so it's a drop-in engine.
 */
@Singleton
class VoskStt @Inject constructor(@ApplicationContext private val context: Context) {
  private val modelRoot = File(context.filesDir, "vosk")
  private val modelDir = File(modelRoot, MODEL_NAME)
  private var model: Model? = null
  private var service: SpeechService? = null
  @Volatile private var ready = false
  @Volatile private var loading = false

  fun isReady(): Boolean = ready

  fun isDownloaded(): Boolean = File(modelDir, "conf").exists()

  /** Total bytes the recogniser occupies on disk. */
  fun sizeBytes(): Long =
    if (modelRoot.exists()) modelRoot.walk().filter { it.isFile }.sumOf { it.length() } else 0L

  /** Unloads and removes the downloaded recogniser from disk. */
  @Synchronized
  fun delete() {
    runCatching { service?.cancel() }
    service = null
    model = null
    ready = false
    modelRoot.deleteRecursively()
  }

  /** Downloads (if needed) and loads the model. Blocking — call from a background dispatcher. */
  @Synchronized
  fun prepare() {
    if (ready || loading) return
    loading = true
    try {
      if (!isDownloaded()) {
        Log.i(TAG, "Downloading Vosk model…")
        downloadAndUnzip()
      }
      model = Model(modelDir.absolutePath)
      ready = true
      Log.i(TAG, "Vosk ready")
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to prepare Vosk", t)
      ready = false
    } finally {
      loading = false
    }
  }

  /** Begins listening; results map onto [listener]. Must be called from the main thread. */
  fun start(listener: SttManager.Listener) {
    val m = model
    if (m == null) {
      listener.onError("Speech model isn't ready yet.")
      return
    }
    val accumulated = StringBuilder()
    val recognizer = Recognizer(m, SAMPLE_RATE)
    val svc = SpeechService(recognizer, SAMPLE_RATE)
    service = svc
    svc.startListening(
      object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
          val partial = field(hypothesis, "partial")
          if (partial.isNotEmpty()) listener.onPartial(join(accumulated, partial))
        }

        override fun onResult(hypothesis: String?) {
          // Fires at each pause; accumulate so multi-sentence dictation isn't truncated.
          val text = field(hypothesis, "text")
          if (text.isNotEmpty()) {
            if (accumulated.isNotEmpty()) accumulated.append(' ')
            accumulated.append(text)
            listener.onPartial(accumulated.toString())
          }
        }

        override fun onFinalResult(hypothesis: String?) {
          val text = field(hypothesis, "text")
          if (text.isNotEmpty()) {
            if (accumulated.isNotEmpty()) accumulated.append(' ')
            accumulated.append(text)
          }
          listener.onListeningChanged(false)
          val full = accumulated.toString().trim()
          if (full.isNotEmpty()) listener.onFinal(full)
        }

        override fun onError(exception: Exception?) {
          listener.onListeningChanged(false)
          listener.onError(exception?.message ?: "Speech recognition error")
        }

        override fun onTimeout() {
          listener.onListeningChanged(false)
        }
      }
    )
    listener.onListeningChanged(true)
  }

  /** Stops listening and delivers the final transcript via the listener. */
  fun stop() {
    service?.stop()
  }

  fun cancel() {
    service?.cancel()
  }

  private fun join(acc: StringBuilder, partial: String): String =
    if (acc.isEmpty()) partial else "$acc $partial"

  private fun field(json: String?, key: String): String =
    if (json.isNullOrBlank()) "" else runCatching { JSONObject(json).optString(key) }.getOrDefault("")

  private fun downloadAndUnzip() {
    modelRoot.mkdirs()
    val zip = File(modelRoot, "model.zip")
    URL(MODEL_URL).openStream().use { input -> zip.outputStream().use { input.copyTo(it, 1 shl 16) } }
    val rootPath = modelRoot.canonicalPath
    ZipInputStream(zip.inputStream().buffered()).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        val out = File(modelRoot, entry.name)
        // Zip-slip guard.
        if (out.canonicalPath.startsWith(rootPath)) {
          if (entry.isDirectory) {
            out.mkdirs()
          } else {
            out.parentFile?.mkdirs()
            out.outputStream().use { zis.copyTo(it, 1 shl 16) }
          }
        }
        entry = zis.nextEntry
      }
    }
    zip.delete()
  }
}
