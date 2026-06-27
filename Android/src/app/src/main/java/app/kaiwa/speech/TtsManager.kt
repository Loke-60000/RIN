// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

private const val TAG = "AGTts"

/**
 * Thin wrapper over the built-in Android [TextToSpeech] engine. Speaks the assistant's replies
 * aloud — no model download, negligible battery impact (the system engine runs the synthesis).
 */
class TtsManager(context: Context) {
  @Volatile private var ready = false
  @Volatile private var initFailed = false
  private var engine: TextToSpeech? = null

  /** Invoked once the engine reports init success/failure, so callers can refresh availability UI. */
  var onInitialized: (() -> Unit)? = null

  init {
    engine =
      TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          engine?.language = Locale.getDefault()
          ready = true
        } else {
          // No usable engine — common on de-Googled ROMs (GrapheneOS) with no TTS engine installed.
          initFailed = true
          Log.w(TAG, "TextToSpeech init failed: $status")
        }
        onInitialized?.invoke()
      }
  }

  /** True once we know this device has no working TTS engine, so callers can guide the user. */
  fun isUnavailable(): Boolean = initFailed

  /** Speaks [text] (Markdown stripped to clean prose), interrupting anything currently spoken. */
  fun speak(text: String) {
    val e = engine ?: return
    val clean = stripMarkdown(text)
    if (!ready || clean.isEmpty()) return
    e.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "okgemma")
  }

  fun stop() {
    engine?.stop()
  }

  fun shutdown() {
    engine?.stop()
    engine?.shutdown()
    engine = null
  }
}

/** Strips the most common Markdown noise so the TTS engine reads clean prose. */
private fun stripMarkdown(md: String): String =
  md
    .replace(Regex("```[\\s\\S]*?```"), " code block ")
    .replace(Regex("`([^`]*)`"), "$1")
    .replace(Regex("(?m)^#{1,6}\\s*"), "")
    .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
    .replace(Regex("\\*([^*]+)\\*"), "$1")
    .replace(Regex("(?m)^\\s*[-*]\\s+"), "")
    .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
    .trim()
