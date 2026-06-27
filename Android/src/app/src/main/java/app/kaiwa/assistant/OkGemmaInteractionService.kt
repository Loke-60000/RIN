// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * The top-level voice interaction service. Declaring this (and selecting Gemma as the device's
 * digital assistant) is what lets the long-press-power-button / assist gesture summon Gemma. The
 * actual UI lives in the session started by [OkGemmaSessionService].
 */
class OkGemmaInteractionService : VoiceInteractionService() {
  override fun onReady() {
    super.onReady()
    Log.d(TAG, "Gemma voice interaction service ready")
  }

  companion object {
    private const val TAG = "AGOkGemmaVIS"
  }
}
