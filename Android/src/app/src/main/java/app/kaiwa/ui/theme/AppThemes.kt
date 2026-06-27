// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The selectable color themes (Settings → Theme). [id] maps to `assets/themes/<id>.json`; [accent]
 * and [paper] are just the swatch preview in the picker (the full palette lives in the JSON).
 */
data class AppThemeOption(val id: String, val label: String, val accent: Color, val paper: Color)

val APP_THEMES =
  listOf(
    AppThemeOption("default", "Default", Color(0xFFE8503A), Color(0xFFFFFFFF)),
    AppThemeOption("gemma", "Gemma", Color(0xFF1A73E8), Color(0xFFFFFFFF)),
    AppThemeOption("gemini", "Gemini", Color(0xFF1A73E8), Color(0xFFFFFFFF)),
    AppThemeOption("chatgpt", "ChatGPT", Color(0xFF10A37F), Color(0xFFFFFFFF)),
    AppThemeOption("mistral", "Mistral", Color(0xFFFA500F), Color(0xFFFFFCF8)),
    AppThemeOption("claude", "Claude", Color(0xFFD97757), Color(0xFFFAF9F5)),
  )

fun appThemeLabel(id: String): String = APP_THEMES.firstOrNull { it.id == id }?.label ?: "Default"

/** Background modes (Settings → Background). [paper] is the swatch preview. */
data class AppBackgroundOption(val id: String, val labelKey: String, val labelDefault: String, val paper: Color, val ink: Color)

val APP_BACKGROUNDS =
  listOf(
    AppBackgroundOption("white", "bg.white", "White", Color(0xFFFFFFFF), Color(0xFF1F1F1F)),
    AppBackgroundOption("dark", "bg.dark", "Dark", Color(0xFF1E1F20), Color(0xFFE3E3E3)),
    AppBackgroundOption("beige", "bg.beige", "Beige", Color(0xFFFAF9F5), Color(0xFF141413)),
  )

fun appBackgroundLabelDefault(id: String): String =
  APP_BACKGROUNDS.firstOrNull { it.id == id }?.labelDefault ?: "White"
