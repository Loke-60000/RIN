// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.content.Context
import app.kaiwa.i18n.appLanguageEnglishName
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/** Lightweight user preferences for the Gemma chat experience. */
class ChatPrefs(context: Context) {
  private val prefs = context.getSharedPreferences("ok_gemma_chat", Context.MODE_PRIVATE)
  private val gson = Gson()

  var username: String
    get() = prefs.getString(KEY_USERNAME, "") ?: ""
    set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

  /** When true, conversations are not persisted to disk. */
  var ghostMode: Boolean
    get() = prefs.getBoolean(KEY_GHOST_MODE, false)
    set(value) = prefs.edit().putBoolean(KEY_GHOST_MODE, value).apply()

  /** Name of the user's preferred default model, if any. */
  var defaultModelName: String?
    get() = prefs.getString(KEY_DEFAULT_MODEL, null)
    set(value) = prefs.edit().putString(KEY_DEFAULT_MODEL, value).apply()

  /** The system prompt sent to the model. User-editable in settings. */
  var systemPrompt: String
    get() = prefs.getString(KEY_SYSTEM_PROMPT, null) ?: DEFAULT_SYSTEM_PROMPT
    set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

  /** Builds the effective prompt, weaving in the user's name when set. */
  /** UI + response language code (e.g. "fr"), or null to follow the device language. */
  var appLanguage: String?
    get() = prefs.getString(KEY_APP_LANGUAGE, null)
    set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

  /** Selected accent id (gemini / chatgpt / mistral / claude). */
  var theme: String
    get() = prefs.getString(KEY_THEME, "gemini") ?: "gemini"
    set(value) = prefs.edit().putString(KEY_THEME, value).apply()

  /** Selected background mode (white / dark / beige). */
  var backgroundMode: String
    get() = prefs.getString(KEY_BACKGROUND, "white") ?: "white"
    set(value) = prefs.edit().putString(KEY_BACKGROUND, value).apply()

  fun effectiveSystemPrompt(): String {
    var prompt = systemPrompt
    // Ghost mode keeps the user anonymous: the model is told the name is "Anonymous", not the real one.
    val name = if (ghostMode) "Anonymous" else username.trim()
    if (name.isNotBlank()) prompt += "\n\nThe user's name is $name."
    appLanguageEnglishName(appLanguage)?.let { lang ->
      prompt += "\n\nAlways respond in $lang, whatever language the user writes in."
    }
    return prompt
  }

  // ---- Model renaming (modelName -> custom display name) ----

  private fun renames(): MutableMap<String, String> {
    val json = prefs.getString(KEY_RENAMES, null) ?: return mutableMapOf()
    return try {
      gson.fromJson(json, object : TypeToken<MutableMap<String, String>>() {}.type)
    } catch (e: Exception) {
      mutableMapOf()
    }
  }

  fun displayNameFor(modelName: String, fallback: String): String {
    val alias = renames()[modelName]?.trim()
    return if (!alias.isNullOrEmpty()) alias else fallback
  }

  fun setRename(modelName: String, alias: String) {
    val map = renames()
    if (alias.isBlank()) map.remove(modelName) else map[modelName] = alias.trim()
    prefs.edit().putString(KEY_RENAMES, gson.toJson(map)).apply()
  }

  // ---- API models ----

  fun apiModels(): List<ApiModelConfig> {
    val json = prefs.getString(KEY_API_MODELS, null) ?: return emptyList()
    return try {
      gson.fromJson(json, object : TypeToken<List<ApiModelConfig>>() {}.type)
    } catch (e: Exception) {
      emptyList()
    }
  }

  fun saveApiModel(config: ApiModelConfig) {
    val list = apiModels().toMutableList()
    val idx = list.indexOfFirst { it.id == config.id }
    if (idx >= 0) list[idx] = config else list.add(config)
    prefs.edit().putString(KEY_API_MODELS, gson.toJson(list)).apply()
  }

  fun deleteApiModel(id: String) {
    val list = apiModels().filter { it.id != id }
    prefs.edit().putString(KEY_API_MODELS, gson.toJson(list)).apply()
  }

  // ---- API credentials (provider account: base URL + key, reused by many models) ----

  fun apiCredentials(): List<ApiCredential> {
    val json = prefs.getString(KEY_API_CREDENTIALS, null) ?: return emptyList()
    return try {
      // Gson skips the data-class constructor, so a missing autoEnableModels would land on the JVM
      // default (false). Read it off the raw JSON instead so creds saved before the field existed
      // keep their original auto behaviour (true).
      JsonParser.parseString(json).asJsonArray.map { el ->
        val obj = el.asJsonObject
        gson.fromJson(obj, ApiCredential::class.java)
          .copy(autoEnableModels = obj.get("autoEnableModels")?.asBoolean ?: true)
      }
    } catch (e: Exception) {
      emptyList()
    }
  }

  fun saveApiCredential(cred: ApiCredential) {
    val list = apiCredentials().toMutableList()
    val idx = list.indexOfFirst { it.id == cred.id }
    if (idx >= 0) list[idx] = cred else list.add(cred)
    prefs.edit().putString(KEY_API_CREDENTIALS, gson.toJson(list)).apply()
  }

  fun deleteApiCredential(id: String) {
    val list = apiCredentials().filter { it.id != id }
    prefs.edit().putString(KEY_API_CREDENTIALS, gson.toJson(list)).apply()
  }

  /** API key of the first saved Ollama provider, so web search can reuse it (no re-typing it). */
  fun ollamaProviderKey(): String =
    apiCredentials()
      .firstOrNull { it.provider == ApiProvider.OLLAMA || it.provider == ApiProvider.OLLAMA_CLOUD }
      ?.apiKey
      .orEmpty()

  /** The id of the active API model (so the assistant popup can use it); null = on-device model. */
  var activeApiModelId: String?
    get() = prefs.getString(KEY_ACTIVE_API_MODEL, null)
    set(value) = prefs.edit().putString(KEY_ACTIVE_API_MODEL, value).apply()

  /**
   * Name of the model currently being handed to the native engine. Set before the (uncatchable)
   * native init and cleared once it returns; if it survives a process death, that model crashed us,
   * so we won't auto-load it again. Null = no load in flight.
   */
  var loadingModel: String?
    get() = prefs.getString(KEY_LOADING_MODEL, null)
    set(value) = prefs.edit().putString(KEY_LOADING_MODEL, value).apply()

  /** Accelerator label used for the in-flight load, so a crash can be attributed to the backend. */
  var loadingBackend: String?
    get() = prefs.getString(KEY_LOADING_BACKEND, null)
    set(value) = prefs.edit().putString(KEY_LOADING_BACKEND, value).apply()

  /** Models whose GPU engine crashed — load them on CPU instead (slower, but works). */
  private fun forceCpuModels(): Set<String> =
    prefs.getStringSet(KEY_FORCE_CPU_MODELS, emptySet()) ?: emptySet()

  fun isModelForceCpu(name: String): Boolean = forceCpuModels().contains(name)

  fun addForceCpuModel(name: String) {
    prefs.edit().putStringSet(KEY_FORCE_CPU_MODELS, forceCpuModels() + name).apply()
  }

  /** Clears all CPU demotions so GPU is retried — used to heal false positives on a clean start. */
  fun clearForceCpuModels() {
    prefs.edit().remove(KEY_FORCE_CPU_MODELS).apply()
  }

  /** Models that crashed even on CPU — genuinely unloadable on this device, never tried again. */
  private fun poisonedModels(): Set<String> =
    prefs.getStringSet(KEY_POISONED_MODELS, emptySet()) ?: emptySet()

  fun isModelPoisoned(name: String): Boolean = poisonedModels().contains(name)

  fun addPoisonedModel(name: String) {
    prefs.edit().putStringSet(KEY_POISONED_MODELS, poisonedModels() + name).apply()
  }

  // ---- Web search ----

  /** One of: NONE, DUCKDUCKGO, SEARXNG, OLLAMA. */
  var webSearchProvider: String
    get() = prefs.getString(KEY_WEBSEARCH_PROVIDER, "NONE") ?: "NONE"
    set(value) = prefs.edit().putString(KEY_WEBSEARCH_PROVIDER, value).apply()

  var searxngUrl: String
    get() = prefs.getString(KEY_SEARXNG_URL, "") ?: ""
    set(value) = prefs.edit().putString(KEY_SEARXNG_URL, value).apply()

  var ollamaSearchKey: String
    get() = prefs.getString(KEY_OLLAMA_SEARCH_KEY, "") ?: ""
    set(value) = prefs.edit().putString(KEY_OLLAMA_SEARCH_KEY, value).apply()

  // ---- RAG ----

  var ragEnabled: Boolean
    get() = prefs.getBoolean(KEY_RAG_ENABLED, false)
    set(value) = prefs.edit().putBoolean(KEY_RAG_ENABLED, value).apply()

  /**
   * Whether the mic may fall back to the system speech-to-text recogniser for text-only models.
   * Defaults to whether a recogniser is actually installed, so de-Googled ROMs (GrapheneOS) — which
   * have none — start with it off instead of hitting a dead recogniser.
   */
  // Off by default: system speech-to-text is unreliable on de-Googled ROMs (GrapheneOS), where the
  // default recogniser often errors even though one is nominally "available". Users opt in.
  var sttEnabled: Boolean
    get() = prefs.getBoolean(KEY_STT_ENABLED, false)
    set(value) = prefs.edit().putBoolean(KEY_STT_ENABLED, value).apply()

  /** Open the assistant by double-tapping the back of the phone (off by default). */
  var backTapEnabled: Boolean
    get() = prefs.getBoolean(KEY_BACK_TAP, false)
    set(value) = prefs.edit().putBoolean(KEY_BACK_TAP, value).apply()

  /** Back-tap sensitivity, 0 (needs a firm knock) to 100 (gentlest tap). */
  var backTapSensitivity: Int
    get() = prefs.getInt(KEY_BACK_TAP_SENSITIVITY, DEFAULT_BACK_TAP_SENSITIVITY)
    set(value) = prefs.edit().putInt(KEY_BACK_TAP_SENSITIVITY, value.coerceIn(0, 100)).apply()

  /** Let the assistant capture and see the screen (off by default; needs a screen-capture grant). */
  var screenReadingEnabled: Boolean
    get() = prefs.getBoolean(KEY_SCREEN_READING, false)
    set(value) = prefs.edit().putBoolean(KEY_SCREEN_READING, value).apply()

  /** Locale of the active on-device voice (e.g. "fr_FR"), or null to prefer the system TTS engine. */
  var ttsVoice: String?
    get() = prefs.getString(KEY_TTS_VOICE, null)
    set(value) = prefs.edit().putString(KEY_TTS_VOICE, value).apply()

  /** Model the assistant popup uses: "api:<id>" / "local:<name>", or null to match the chat model. */
  var popupModel: String?
    get() = prefs.getString(KEY_POPUP_MODEL, null)
    set(value) = prefs.edit().putString(KEY_POPUP_MODEL, value).apply()

  /** Model that writes chat titles: "api:<id>" / "local:<name>", or null to match the chat model. */
  var titleModel: String?
    get() = prefs.getString(KEY_TITLE_MODEL, null)
    set(value) = prefs.edit().putString(KEY_TITLE_MODEL, value).apply()

  companion object {
    private const val KEY_USERNAME = "username"
    private const val KEY_GHOST_MODE = "ghost_mode"
    private const val KEY_DEFAULT_MODEL = "default_model"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val KEY_RENAMES = "model_renames"
    private const val KEY_API_MODELS = "api_models"
    private const val KEY_API_CREDENTIALS = "api_credentials"
    private const val KEY_ACTIVE_API_MODEL = "active_api_model"
    private const val KEY_LOADING_MODEL = "loading_model"
    private const val KEY_LOADING_BACKEND = "loading_backend"
    private const val KEY_FORCE_CPU_MODELS = "force_cpu_models"
    private const val KEY_POISONED_MODELS = "poisoned_models"
    private const val KEY_WEBSEARCH_PROVIDER = "websearch_provider"
    private const val KEY_SEARXNG_URL = "searxng_url"
    private const val KEY_OLLAMA_SEARCH_KEY = "ollama_search_key"
    private const val KEY_RAG_ENABLED = "rag_enabled"
    private const val KEY_STT_ENABLED = "stt_enabled"
    private const val KEY_BACK_TAP = "back_tap_enabled"
    private const val KEY_BACK_TAP_SENSITIVITY = "back_tap_sensitivity"
    private const val KEY_SCREEN_READING = "screen_reading_enabled"
    // Midpoint of the 3–9 m/s² band (~6) — a balanced start the user can shift either way.
    const val DEFAULT_BACK_TAP_SENSITIVITY = 50
    private const val KEY_TTS_VOICE = "tts_voice"
    private const val KEY_POPUP_MODEL = "popup_model"
    private const val KEY_TITLE_MODEL = "title_model"
    private const val KEY_APP_LANGUAGE = "app_language"
    private const val KEY_THEME = "app_theme"
    private const val KEY_BACKGROUND = "app_background"

    const val DEFAULT_SYSTEM_PROMPT =
      "You are Rin, a helpful, friendly, privacy-first AI assistant made by Gadget-Lab. " +
        "Everything the user says stays on their device — chats and memory are stored locally, never on a server. " +
        "Answer conversationally and concisely. You can perform device actions " +
        "(launch apps, flashlight, contacts, email, maps, Wi-Fi settings, calendar) by calling the " +
        "provided functions when the user clearly asks for them. When given on-screen text, use it to answer."
  }
}
