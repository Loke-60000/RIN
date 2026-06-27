// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import org.json.JSONObject

/**
 * JSON-driven translations. Strings live in `assets/i18n/<lang>.json` (e.g. `en.json`, `fr.json`),
 * looked up by key. The language is the user's chosen [override] (Settings → Preferences → Language)
 * or the device language, falling back to English; an unknown key falls back to the supplied default.
 * Add a language by dropping in a new `<lang>.json` — no code changes needed.
 */
object Strings {
  // Bumped on every language change so `tr()` recomposes the whole UI live.
  var version by mutableIntStateOf(0)
    private set

  @Volatile private var loadedLang: String? = null
  private var map: Map<String, String> = emptyMap()
  /** User-chosen UI language code (e.g. "fr"), or null to follow the device. */
  @Volatile private var override: String? = null

  private fun targetLang(): String = override ?: Locale.getDefault().language

  fun ensureLoaded(context: Context) {
    if (loadedLang == targetLang()) return
    synchronized(this) {
      val target = targetLang()
      if (loadedLang == target) return
      map = load(context, target) ?: load(context, "en") ?: emptyMap()
      loadedLang = target
    }
  }

  /** Sets the UI language (null = device default) and refreshes the UI. */
  fun setLanguage(context: Context, lang: String?) {
    override = lang
    synchronized(this) {
      val target = targetLang()
      map = load(context, target) ?: load(context, "en") ?: emptyMap()
      loadedLang = target
    }
    version++
  }

  private fun load(context: Context, lang: String): Map<String, String>? =
    try {
      val text = context.assets.open("i18n/$lang.json").bufferedReader().use { it.readText() }
      val obj = JSONObject(text)
      buildMap { for (key in obj.keys()) put(key, obj.getString(key)) }
    } catch (e: Exception) {
      null
    }

  /** Translation for [key], or [default] (then [key]) if missing. */
  fun get(key: String, default: String = key): String = map[key] ?: default
}

/** Composable lookup: `Text(tr("settings.your_name", "Your name"))`. */
@Composable
fun tr(key: String, default: String = key): String {
  Strings.ensureLoaded(LocalContext.current)
  @Suppress("UNUSED_EXPRESSION") Strings.version // subscribe so a language change recomposes
  return Strings.get(key, default)
}
