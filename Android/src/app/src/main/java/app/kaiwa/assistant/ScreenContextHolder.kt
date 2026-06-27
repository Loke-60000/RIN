// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import android.app.assist.AssistStructure

/**
 * Process-wide handoff for the text the assistant captured from the foreground app. The
 * [OkGemmaSession] populates this from the [AssistStructure] when the assistant is invoked, and the
 * [AssistantOverlayActivity] reads it to answer "what's on my screen" style questions.
 */
object ScreenContextHolder {
  @Volatile var screenText: String? = null
  /** PNG bytes of a screenshot captured by the assistant session (for vision models). */
  @Volatile var screenshot: ByteArray? = null

  fun clear() {
    screenText = null
    screenshot = null
  }

  /** Walks the assist structure and concatenates all visible text into a single readable blob. */
  fun extractText(structure: AssistStructure): String {
    val builder = StringBuilder()
    val windowCount = structure.windowNodeCount
    for (i in 0 until windowCount) {
      val windowNode = structure.getWindowNodeAt(i)
      appendNodeText(windowNode.rootViewNode, builder)
    }
    return builder.toString().trim().take(MAX_CHARS)
  }

  private fun appendNodeText(node: AssistStructure.ViewNode?, builder: StringBuilder) {
    if (node == null) return

    val text = node.text?.toString()?.trim()
    if (!text.isNullOrEmpty()) {
      builder.append(text).append('\n')
    }
    val hint = node.hint?.trim()
    if (!hint.isNullOrEmpty() && hint != text) {
      builder.append(hint).append('\n')
    }

    val childCount = node.childCount
    for (i in 0 until childCount) {
      appendNodeText(node.getChildAt(i), builder)
    }
  }

  private const val MAX_CHARS = 8000
}
