// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

private const val TAG = "AGStt"

/**
 * Wraps the built-in Android [SpeechRecognizer] for voice input. On Pixel devices the recognition
 * runs on-device (EXTRA_PREFER_OFFLINE), so there's no model to bundle and battery impact is low.
 *
 * Must be created and driven from the main thread — [SpeechRecognizer] requires it.
 */
class SttManager(private val context: Context) {
  interface Listener {
    /** Live transcript as the user speaks. */
    fun onPartial(text: String)

    /** Final transcript once the user stops speaking. */
    fun onFinal(text: String)

    /** A human-readable error (mic denied, no speech, recognizer unavailable, …). */
    fun onError(message: String)

    /** Listening started/stopped — drive the mic UI from this. */
    fun onListeningChanged(listening: Boolean)

    /** Live mic loudness in 0..1, for a voice-reactive waveform. Optional. */
    fun onRms(level: Float) {}
  }

  private var recognizer: SpeechRecognizer? = null
  private var listener: Listener? = null

  fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

  /** Begins listening. Caller must already hold the RECORD_AUDIO permission. */
  fun start(listener: Listener) {
    if (!isAvailable()) {
      listener.onError("Speech recognition isn't available on this device.")
      return
    }
    this.listener = listener
    recognizer?.destroy()
    recognizer =
      SpeechRecognizer.createSpeechRecognizer(context).apply {
        setRecognitionListener(recognitionListener)
        startListening(buildIntent())
      }
  }

  /** Stops listening but lets the recognizer deliver whatever it has so far (fires onFinal). */
  fun stop() {
    recognizer?.stopListening()
  }

  /** Aborts immediately with no final result. */
  fun cancel() {
    recognizer?.cancel()
    listener?.onListeningChanged(false)
  }

  fun destroy() {
    recognizer?.destroy()
    recognizer = null
    listener = null
  }

  private fun buildIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
      )
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      // Prefer on-device recognition to keep it private and power-cheap; the system falls back to
      // network recognition if no offline model is installed.
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

  private val recognitionListener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        listener?.onListeningChanged(true)
      }

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {
        // SpeechRecognizer reports roughly -2 dB (silence) to ~10 dB (loud); map to 0..1.
        listener?.onRms(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
      }

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {
        listener?.onListeningChanged(false)
      }

      override fun onError(error: Int) {
        // Tear the recognizer down so a flaky service can't re-fire onReadyForSpeech and loop.
        recognizer?.cancel()
        listener?.onListeningChanged(false)
        listener?.onError(errorMessage(error))
      }

      override fun onResults(results: Bundle?) {
        listener?.onListeningChanged(false)
        val text = firstResult(results)
        if (text.isNotEmpty()) listener?.onFinal(text)
      }

      override fun onPartialResults(partialResults: Bundle?) {
        val text = firstResult(partialResults)
        if (text.isNotEmpty()) listener?.onPartial(text)
      }

      override fun onEvent(eventType: Int, params: Bundle?) {}
    }

  private fun firstResult(bundle: Bundle?): String =
    bundle
      ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
      ?.firstOrNull()
      .orEmpty()

  private fun errorMessage(error: Int): String =
    when (error) {
      SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
      SpeechRecognizer.ERROR_CLIENT -> "Recognition client error."
      SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
      SpeechRecognizer.ERROR_NETWORK -> "Network error."
      SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout."
      SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again."
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy."
      SpeechRecognizer.ERROR_SERVER -> "Recognition server error."
      SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
      else -> "Speech recognition failed ($error)."
    }.also { Log.w(TAG, "STT error $error: $it") }
}
