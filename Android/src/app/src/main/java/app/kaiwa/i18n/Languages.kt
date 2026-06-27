// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.i18n

/**
 * Languages the user can pick for the app. [native] is shown in the picker (always in its own
 * script); [english] is the name handed to the model so it knows which language to reply in. The UI
 * itself is only translated for codes that have an `assets/i18n/<code>.json`; others fall back to
 * English UI while the model still answers in the chosen language.
 */
data class AppLanguage(val code: String, val native: String, val english: String)

val APP_LANGUAGES =
  listOf(
    AppLanguage("en", "English", "English"),
    AppLanguage("fr", "Français", "French"),
    AppLanguage("es", "Español", "Spanish"),
    AppLanguage("de", "Deutsch", "German"),
    AppLanguage("it", "Italiano", "Italian"),
    AppLanguage("pt", "Português", "Portuguese"),
    AppLanguage("nl", "Nederlands", "Dutch"),
    AppLanguage("ru", "Русский", "Russian"),
    AppLanguage("tr", "Türkçe", "Turkish"),
    AppLanguage("pl", "Polski", "Polish"),
    AppLanguage("ar", "العربية", "Arabic"),
    AppLanguage("hi", "हिन्दी", "Hindi"),
    AppLanguage("zh", "中文", "Chinese"),
    AppLanguage("ja", "日本語", "Japanese"),
    AppLanguage("ko", "한국어", "Korean"),
  )

/** English name for the model's "respond in …" instruction, or null for an unknown/no selection. */
fun appLanguageEnglishName(code: String?): String? =
  APP_LANGUAGES.firstOrNull { it.code == code }?.english

/** Native display name for the picker. */
fun appLanguageNative(code: String?): String? =
  APP_LANGUAGES.firstOrNull { it.code == code }?.native
