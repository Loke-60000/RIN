// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Tiny observable signal bumped each time a one-shot screen capture lands in [ScreenContextHolder].
 * The assistant popup reads [version] so it can flip to "Seeing your screen" the moment the frame is
 * ready, without keeping any screen projection alive.
 */
object ScreenCaptureManager {
  var version by mutableIntStateOf(0)
    private set

  fun markCaptured() {
    version++
  }
}
