// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSession.AssistState
import android.util.Log

/**
 * The session that runs when Gemma is invoked as the assistant (e.g. long-pressing the power
 * button when it's the default digital assistant). It grabs the on-screen text via
 * [onHandleAssist], then launches the [AssistantOverlayActivity] popup and dismisses itself so the
 * Compose UI takes over.
 */
class OkGemmaSession(context: Context) : VoiceInteractionSession(context) {
  private val handler = Handler(Looper.getMainLooper())
  private var launched = false

  override fun onPrepareShow(args: Bundle?, showFlags: Int) {
    super.onPrepareShow(args, showFlags)
    // We render our own translucent activity instead of a session content view.
    setUiEnabled(false)
  }

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    // The framework reuses this session instance across summons, so re-arm the launch guard each
    // time — otherwise only the first power-button press would ever open the popup.
    launched = false
    ScreenContextHolder.clear()
    // If assist data never arrives (e.g. "use text from screen" is off), still open the popup.
    handler.postDelayed({ launchOverlay() }, ASSIST_TIMEOUT_MS)
  }

  override fun onHandleAssist(state: AssistState) {
    super.onHandleAssist(state)
    try {
      val structure = state.assistStructure
      if (structure != null) {
        ScreenContextHolder.screenText = ScreenContextHolder.extractText(structure)
        Log.d(TAG, "Captured ${ScreenContextHolder.screenText?.length ?: 0} chars from screen")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to extract screen text", e)
    }
    launchOverlay()
  }

  override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
    super.onHandleScreenshot(screenshot)
    if (screenshot != null) {
      try {
        val out = java.io.ByteArrayOutputStream()
        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
        ScreenContextHolder.screenshot = out.toByteArray()
        Log.d(TAG, "Captured screenshot ${ScreenContextHolder.screenshot?.size ?: 0} bytes")
      } catch (e: Exception) {
        Log.w(TAG, "Failed to capture screenshot", e)
      }
    }
  }

  override fun onHide() {
    handler.removeCallbacksAndMessages(null)
    super.onHide()
  }

  private fun launchOverlay() {
    if (launched) return
    launched = true
    handler.removeCallbacksAndMessages(null)

    val intent =
      Intent(context, AssistantOverlayActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    try {
      startAssistantActivity(intent)
    } catch (e: Exception) {
      Log.w(TAG, "startAssistantActivity failed, falling back", e)
      try {
        context.startActivity(intent)
      } catch (e2: Exception) {
        Log.e(TAG, "Failed to launch assistant overlay", e2)
      }
    }
    hide()
  }

  companion object {
    private const val TAG = "AGOkGemmaSession"
    private const val ASSIST_TIMEOUT_MS = 400L
  }
}
