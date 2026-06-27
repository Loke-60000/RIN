// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.theme

import androidx.compose.runtime.mutableStateOf
import app.kaiwa.proto.Theme

/** Holds the currently selected theme as observable Compose state. */
object ThemeSettings {
  val themeOverride = mutableStateOf(Theme.THEME_AUTO)
}
