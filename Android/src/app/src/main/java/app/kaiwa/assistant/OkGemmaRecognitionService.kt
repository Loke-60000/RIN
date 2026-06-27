// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * A placeholder recognition service. A [android.service.voice.VoiceInteractionService] must
 * reference a valid recognition service to be selectable as the device's digital assistant. OK
 * Gemma drives input from the popup's text field rather than continuous speech recognition, so this
 * simply reports "no match".
 */
class OkGemmaRecognitionService : RecognitionService() {
  override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
    try {
      listener?.error(SpeechRecognizer.ERROR_NO_MATCH)
    } catch (_: Exception) {}
  }

  override fun onCancel(listener: Callback?) {}

  override fun onStopListening(listener: Callback?) {}
}
