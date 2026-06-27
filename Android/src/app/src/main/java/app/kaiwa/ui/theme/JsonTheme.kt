// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.theme

import android.content.Context
import android.util.Log
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

private const val THEME_PREFS = "ok_gemma_chat"
private const val ACCENT_KEY = "app_theme"
private const val BG_KEY = "app_background"
const val DEFAULT_ACCENT = "default"
const val DEFAULT_BACKGROUND = "white"

/**
 * Data-driven theming composed from two independent choices:
 *  - **Accent** (`assets/accents/<id>.json`, Settings → Theme): the colored roles (primary/secondary…),
 *    with a light + dark variant.
 *  - **Background** (`assets/backgrounds/<mode>.json`, Settings → Background): the neutral roles
 *    (background/surface/outline…) — `white`, `dark`, or `beige`. The mode also decides light vs dark.
 *
 * The final scheme = neutral roles from the background + colored roles from the accent's matching
 * variant. Anything missing (or a parse failure) falls back to the built-in scheme. [version] is
 * Compose state so changing either choice recomposes the whole app live.
 */
object JsonTheme {
  var version by mutableIntStateOf(0)
    private set

  @Volatile private var loaded = false
  private var accentLight: Map<String, Color> = emptyMap()
  private var accentDark: Map<String, Color> = emptyMap()
  private var neutral: Map<String, Color> = emptyMap()
  @Volatile private var darkMode = false

  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      val sp = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
      loadAccent(context, sp.getString(ACCENT_KEY, DEFAULT_ACCENT) ?: DEFAULT_ACCENT)
      loadBackground(context, sp.getString(BG_KEY, DEFAULT_BACKGROUND) ?: DEFAULT_BACKGROUND)
      loaded = true
    }
  }

  /** True when the chosen background is the dark mode (drives status bar + which accent variant). */
  fun isDark(): Boolean = darkMode

  /** Switches the accent color and refreshes the UI. */
  fun setAccent(context: Context, id: String) {
    loadAccent(context, id)
    version++
  }

  /** Switches the background mode (white / dark / beige) and refreshes the UI. */
  fun setBackground(context: Context, mode: String) {
    loadBackground(context, mode)
    version++
  }

  @Synchronized
  private fun loadAccent(context: Context, id: String) {
    try {
      val obj = JSONObject(context.assets.open("accents/$id.json").bufferedReader().use { it.readText() })
      accentLight = parsePalette(obj.optJSONObject("light"))
      accentDark = parsePalette(obj.optJSONObject("dark"))
    } catch (e: Exception) {
      Log.w("JsonTheme", "Failed to load accent '$id'", e)
      accentLight = emptyMap()
      accentDark = emptyMap()
    }
  }

  @Synchronized
  private fun loadBackground(context: Context, mode: String) {
    try {
      neutral = parsePalette(JSONObject(context.assets.open("backgrounds/$mode.json").bufferedReader().use { it.readText() }))
    } catch (e: Exception) {
      Log.w("JsonTheme", "Failed to load background '$mode'", e)
      neutral = emptyMap()
    }
    darkMode = mode == "dark"
  }

  /** Composes neutral (background) + colored (accent) roles onto [fallback]. */
  fun scheme(fallback: ColorScheme): ColorScheme {
    var s = fallback
    for ((role, c) in neutral) s = apply(s, role, c)
    val accent = if (darkMode) accentDark else accentLight
    for ((role, c) in accent) s = apply(s, role, c)
    return s
  }

  private fun apply(s: ColorScheme, role: String, c: Color): ColorScheme =
    when (role) {
      "primary" -> s.copy(primary = c)
      "onPrimary" -> s.copy(onPrimary = c)
      "primaryContainer" -> s.copy(primaryContainer = c)
      "onPrimaryContainer" -> s.copy(onPrimaryContainer = c)
      "secondary" -> s.copy(secondary = c)
      "onSecondary" -> s.copy(onSecondary = c)
      "secondaryContainer" -> s.copy(secondaryContainer = c)
      "onSecondaryContainer" -> s.copy(onSecondaryContainer = c)
      "tertiary" -> s.copy(tertiary = c)
      "onTertiary" -> s.copy(onTertiary = c)
      "tertiaryContainer" -> s.copy(tertiaryContainer = c)
      "onTertiaryContainer" -> s.copy(onTertiaryContainer = c)
      "error" -> s.copy(error = c)
      "onError" -> s.copy(onError = c)
      "errorContainer" -> s.copy(errorContainer = c)
      "onErrorContainer" -> s.copy(onErrorContainer = c)
      "background" -> s.copy(background = c)
      "onBackground" -> s.copy(onBackground = c)
      "surface" -> s.copy(surface = c)
      "onSurface" -> s.copy(onSurface = c)
      "surfaceVariant" -> s.copy(surfaceVariant = c)
      "onSurfaceVariant" -> s.copy(onSurfaceVariant = c)
      "surfaceContainerLowest" -> s.copy(surfaceContainerLowest = c)
      "surfaceContainerLow" -> s.copy(surfaceContainerLow = c)
      "surfaceContainer" -> s.copy(surfaceContainer = c)
      "surfaceContainerHigh" -> s.copy(surfaceContainerHigh = c)
      "surfaceContainerHighest" -> s.copy(surfaceContainerHighest = c)
      "outline" -> s.copy(outline = c)
      "outlineVariant" -> s.copy(outlineVariant = c)
      else -> s
    }

  private fun parsePalette(obj: JSONObject?): Map<String, Color> {
    if (obj == null) return emptyMap()
    val out = mutableMapOf<String, Color>()
    for (key in obj.keys()) {
      val hex = obj.optString(key, "")
      runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()?.let { out[key] = it }
    }
    return out
  }
}
