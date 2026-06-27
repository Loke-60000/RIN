// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaiwa.assistant.BackTapService
import app.kaiwa.chat.tools.McpManager
import app.kaiwa.chat.tools.PermissionDecision
import app.kaiwa.chat.tools.SkillManager
import app.kaiwa.chat.tools.UiPermissionGate
import app.kaiwa.data.Model
import app.kaiwa.proto.McpAuth
import app.kaiwa.proto.Skill
import app.kaiwa.i18n.Strings
import app.kaiwa.ui.theme.JsonTheme
import app.kaiwa.speech.SttManager
import app.kaiwa.speech.TtsManager
import app.kaiwa.speech.TtsVoice
import app.kaiwa.speech.TtsVoiceManager
import app.kaiwa.speech.VoskStt
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGMainChatVM"
private const val TITLE_SYSTEM_PROMPT =
  "You name chat conversations. Reply with ONLY a concise 3-5 word title — no quotes, no preamble."
private const val IMAGE_ONLY_PROMPT = "What's in this image?"

/** Separates reasoning from the answer. Handles the thought channel, a full `<think>...</think>`
 * block, and a lone closing `</think>` (DeepSeek-R1 etc.). Returns (thinking, answer). */
fun splitThinking(rawAnswer: String, channelThinking: String): Pair<String, String> {
  if (channelThinking.isNotBlank()) return channelThinking.trim() to rawAnswer.trim()
  val open = rawAnswer.indexOf("<think>")
  val close = rawAnswer.indexOf("</think>")
  return when {
    open >= 0 && close > open ->
      rawAnswer.substring(open + 7, close).trim() to
        (rawAnswer.substring(0, open) + rawAnswer.substring(close + 8)).trim()
    open >= 0 -> rawAnswer.substring(open + 7).trim() to ""
    close >= 0 -> rawAnswer.substring(0, close).trim() to rawAnswer.substring(close + 8).trim()
    else -> "" to rawAnswer.trim()
  }
}

enum class ChatPhase {
  INITIALIZING,
  NO_MODEL,
  READY,
  ERROR,
}

data class ChatUiState(
  val phase: ChatPhase = ChatPhase.INITIALIZING,
  val modelName: String = "",
  val isApiModel: Boolean = false,
  val messages: List<ChatMessage> = listOf(),
  val streamingText: String = "",
  val streamingThinking: String = "",
  val streaming: Boolean = false,
  val ghostMode: Boolean = false,
  val username: String = "",
  val systemPrompt: String = "",
  val supportsImage: Boolean = false,
  val supportsAudio: Boolean = false,
  val conversations: List<Conversation> = listOf(),
  val apiModels: List<ApiModelConfig> = listOf(),
  val apiCredentials: List<ApiCredential> = listOf(),
  /** Model ids fetched live from each provider's endpoint, keyed by credential id. */
  val fetchedByProvider: Map<String, List<String>> = emptyMap(),
  /** Credential id whose models are currently being fetched, or null. */
  val fetchingProvider: String? = null,
  val webSearchProvider: String = "NONE",
  val searxngUrl: String = "",
  val ollamaSearchKey: String = "",
  val ragEnabled: Boolean = false,
  val ragDocs: List<RagDoc> = listOf(),
  /** Whether the mic may use the system speech-to-text recogniser (off by default on GrapheneOS). */
  val sttEnabled: Boolean = false,
  /** Open the assistant by double-tapping the back of the phone (off by default). */
  val backTapEnabled: Boolean = false,
  /** Back-tap sensitivity, 0 (firm knock) to 100 (gentlest tap). */
  val backTapSensitivity: Int = 50,
  /** Whether the assistant may capture and see the screen (off by default). */
  val screenReadingEnabled: Boolean = false,
  /** True when replies can be spoken — a system TTS engine exists, or an on-device voice is installed. */
  val canSpeak: Boolean = false,
  /** Locale of the active on-device voice (e.g. "fr_FR"), or null to use the system engine. */
  val ttsVoice: String? = null,
  /** Locales of on-device voices currently installed. */
  val installedVoices: List<String> = listOf(),
  /** Locale of a voice currently downloading, or null. */
  val downloadingVoice: String? = null,
  /** Download progress 0..1 for [downloadingVoice]. */
  val voiceProgress: Float = 0f,
  /** Whether the on-device speech-to-text recogniser (Vosk) is downloaded, and its size. */
  val sttDownloaded: Boolean = false,
  val sttSize: Long = 0L,
  val sttInstalling: Boolean = false,
  /** Whether the memory embedder (MobileCLIP) is downloaded, and its size. */
  val embedderDownloaded: Boolean = false,
  val embedderSize: Long = 0L,
  val embedderInstalling: Boolean = false,
  /** Total bytes of installed on-device voices. */
  val voicesSize: Long = 0L,
  /** Configured popup / chat-title models ("api:<id>" / "local:<name>" / null = same as chat). */
  val popupModel: String? = null,
  val titleModel: String? = null,
  /** Chosen UI + response language code (e.g. "fr"), or null to follow the device. */
  val appLanguage: String? = null,
  /** Selected accent id (gemini / chatgpt / mistral / claude). */
  val theme: String = "gemini",
  /** Selected background mode (white / dark / beige). */
  val backgroundMode: String = "white",
  val errorMessage: String = "",
  /** True while a web search is running, before the answer streams. */
  val searchingWeb: Boolean = false,
  /** True while the mic is capturing speech for voice-to-text input. */
  val listening: Boolean = false,
  /** Live transcript shown in the composer while the user is speaking. */
  val partialTranscript: String = "",
  /** Live mic loudness 0..1 for the voice-reactive waveform (STT path). */
  val voiceLevel: Float = 0f,
)

/** State for the one-shot "tool" pages (Transcribe, Translate) — independent of the chat. */
data class ToolUiState(val running: Boolean = false, val output: String = "", val error: String = "")

@HiltViewModel
class MainChatViewModel
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
  private val engine: ChatEngine,
  private val embedder: MobileClipEmbedder,
  private val voskStt: VoskStt,
  private val ttsVoices: TtsVoiceManager,
  private val mcpManager: McpManager,
  private val skillManager: SkillManager,
  private val permissionGate: UiPermissionGate,
) : ViewModel() {

  private val store = ChatStore(appContext)
  private val prefs = ChatPrefs(appContext)
  private val rag = RagStore(appContext, embedder)
  private val webSearch = WebSearchEngine(prefs)
  private val remote = RemoteChatClient()
  private val tts = TtsManager(appContext)
  private val stt = SttManager(appContext)

  private val _uiState = MutableStateFlow(ChatUiState())
  val uiState = _uiState.asStateFlow()

  private val _toolState = MutableStateFlow(ToolUiState())
  val toolState = _toolState.asStateFlow()
  private var toolJob: kotlinx.coroutines.Job? = null

  /** MCP servers + their tools (connection state, enable/always-allow), for the MCP settings page. */
  val mcpState = mcpManager.state
  /** The skill catalog (built-in + custom), for the Skills settings page. */
  val skills = skillManager.skills
  /** Pending MCP tool-call confirmation to surface as a dialog, or null. */
  val permissionRequest = permissionGate.pending

  private var noModel = false
  private var autoResolved = false
  private var activeApi: ApiModelConfig? = null
  private var current: Conversation = newConversation()
  private var streamJob: kotlinx.coroutines.Job? = null
  private var loadJob: kotlinx.coroutines.Job? = null
  private var pendingUser: ChatMessage? = null
  private var pendingUserPersisted = false
  private var pendingSources: List<SearchResult> = emptyList()
  // True until this conversation gets an auto-generated title (set once per conversation).
  private var needsTitle = true

  init {
    // Apply the saved UI language before the first frame so the whole app starts in it.
    Strings.setLanguage(appContext, prefs.appLanguage)
    _uiState.update {
      it.copy(
        ghostMode = prefs.ghostMode,
        username = prefs.username,
        appLanguage = prefs.appLanguage,
        systemPrompt = prefs.systemPrompt,
        apiModels = prefs.apiModels(),
        apiCredentials = prefs.apiCredentials(),
        webSearchProvider = prefs.webSearchProvider,
        searxngUrl = prefs.searxngUrl,
        ollamaSearchKey = prefs.ollamaSearchKey,
        ragEnabled = prefs.ragEnabled,
        sttEnabled = prefs.sttEnabled,
        backTapEnabled = prefs.backTapEnabled,
        backTapSensitivity = prefs.backTapSensitivity,
        screenReadingEnabled = prefs.screenReadingEnabled,
        ttsVoice = prefs.ttsVoice,
        installedVoices = ttsVoices.installedLocales(),
        canSpeak = computeCanSpeak(),
        popupModel = prefs.popupModel,
        titleModel = prefs.titleModel,
        theme = prefs.theme,
        backgroundMode = prefs.backgroundMode,
      )
    }
    // The system TTS engine reports availability asynchronously; refresh once it does.
    tts.onInitialized = { _uiState.update { it.copy(canSpeak = computeCanSpeak()) } }
    viewModelScope.launch(Dispatchers.IO) {
      val conversations = store.listConversations()
      val docs = rag.listDocuments()
      _uiState.update { it.copy(conversations = conversations, ragDocs = docs) }
      // If semantic RAG is on, bring the embedder up (downloads on first run, else just loads).
      if (prefs.ragEnabled) embedder.prepare()
      // If voice typing is on and the Vosk model is already downloaded, load it for on-device STT.
      if (prefs.sttEnabled && voskStt.isDownloaded()) voskStt.prepare()
      refreshModelStorage()
    }
    // Resume the back-tap gesture service if the user left it on.
    if (prefs.backTapEnabled) BackTapService.start(appContext)
    // Warm the MCP/skill catalogs so the settings pages and tool prompts have them ready (idempotent
    // with the engine's own warm-up).
    warmTools()
    viewModelScope.launch {
      engine.state.collect { es ->
        if (noModel || activeApi != null) return@collect
        val phase =
          when (es.status) {
            ChatEngine.Status.READY -> ChatPhase.READY
            ChatEngine.Status.ERROR -> ChatPhase.ERROR
            else -> ChatPhase.INITIALIZING
          }
        _uiState.update {
          it.copy(
            phase = phase,
            modelName = prefs.displayNameFor(engine.model?.name ?: es.modelName, es.modelName),
            isApiModel = false,
            supportsImage = es.supportsImage,
            supportsAudio = es.supportsAudio,
            errorMessage = es.error,
          )
        }
      }
    }
  }

  private fun newConversation(): Conversation {
    val now = System.currentTimeMillis()
    return Conversation(
      id = UUID.randomUUID().toString(),
      title = "New chat",
      createdAt = now,
      updatedAt = now,
    )
  }

  fun displayNameFor(name: String, fallback: String) = prefs.displayNameFor(name, fallback)

  /** Fetches the model list from a provider's endpoint into [ChatUiState.fetchedModels]. */
  /** Lazily fetches a provider's model list (once); results cached per credential id. */
  fun fetchModels(cred: ApiCredential) {
    if (_uiState.value.fetchingProvider == cred.id) return
    _uiState.update { it.copy(fetchingProvider = cred.id) }
    viewModelScope.launch(Dispatchers.Default) {
      val ids = remote.listModels(cred)
      _uiState.update {
        it.copy(fetchingProvider = null, fetchedByProvider = it.fetchedByProvider + (cred.id to ids))
      }
      // Bring already-enabled Ollama models' vision flag up to date with the server.
      refreshOllamaCapabilities(cred)
    }
  }

  fun clearFetchedModels() {
    _uiState.update { it.copy(fetchedByProvider = emptyMap(), fetchingProvider = null) }
  }

  /**
   * Reads an assistant message aloud. Prefers a selected on-device voice (works on GrapheneOS and
   * for cloud models with no TTS of their own); otherwise falls back to the system engine.
   */
  fun speak(text: String) {
    val locale = activeVoiceLocale()
    if (locale != null) {
      viewModelScope.launch(Dispatchers.IO) { ttsVoices.speak(text, locale) }
    } else {
      tts.speak(text)
    }
  }

  fun stopSpeaking() {
    tts.stop()
    ttsVoices.stop()
  }

  /** The on-device voice to speak with, or null to use the system engine. */
  private fun activeVoiceLocale(): String? {
    val sel = prefs.ttsVoice
    return if (sel != null && ttsVoices.isInstalled(sel)) sel else null
  }

  /** Replies can be spoken if a system engine exists, or an on-device voice is installed. */
  private fun computeCanSpeak(): Boolean = ttsVoices.isAvailable() || !tts.isUnavailable()

  /** True on ABIs where the on-device voice engine is shipped (arm64); gates the voices UI. */
  fun ttsEngineSupported(): Boolean = ttsVoices.supported()

  /** True if the device has a working system TTS engine (false on GrapheneOS without one). */
  fun systemTtsAvailable(): Boolean = !tts.isUnavailable()

  /** Refreshes the on-device storage figures (STT / embedder / voices) shown in the Models page. */
  private fun refreshModelStorage() {
    _uiState.update {
      it.copy(
        sttDownloaded = voskStt.isDownloaded(),
        sttSize = voskStt.sizeBytes(),
        embedderDownloaded = embedder.isDownloaded(),
        embedderSize = embedder.sizeBytes(),
        installedVoices = ttsVoices.installedLocales(),
        voicesSize = ttsVoices.sizeBytes(),
      )
    }
  }

  /** Downloads the on-device speech-to-text recogniser and turns voice typing on. */
  fun installSttModel() {
    prefs.sttEnabled = true
    _uiState.update { it.copy(sttEnabled = true, sttInstalling = true) }
    viewModelScope.launch(Dispatchers.IO) {
      voskStt.prepare()
      refreshModelStorage()
      _uiState.update { it.copy(sttInstalling = false) }
    }
  }

  /** Downloads the memory embedder and turns document memory on. */
  fun installEmbedderModel() {
    prefs.ragEnabled = true
    _uiState.update { it.copy(ragEnabled = true, embedderInstalling = true) }
    viewModelScope.launch(Dispatchers.IO) {
      embedder.prepare()
      refreshModelStorage()
      _uiState.update { it.copy(embedderInstalling = false) }
    }
  }

  /** Removes the on-device speech-to-text recogniser; turns voice typing off. */
  fun deleteSttModel() {
    viewModelScope.launch(Dispatchers.IO) {
      voskStt.delete()
      prefs.sttEnabled = false
      _uiState.update { it.copy(sttEnabled = false) }
      refreshModelStorage()
    }
  }

  /** Removes the memory embedder; turns document memory off. */
  fun deleteEmbedderModel() {
    viewModelScope.launch(Dispatchers.IO) {
      embedder.delete()
      prefs.ragEnabled = false
      _uiState.update { it.copy(ragEnabled = false) }
      refreshModelStorage()
    }
  }

  /** Removes every on-device voice and clears the active selection. */
  fun deleteAllVoices() {
    viewModelScope.launch(Dispatchers.IO) {
      ttsVoices.deleteAll()
      prefs.ttsVoice = null
      _uiState.update { it.copy(ttsVoice = null, canSpeak = computeCanSpeak()) }
      refreshModelStorage()
    }
  }

  /**
   * Deletes all non-LLM on-device models (voices, STT, embedder) and disables the features that use
   * them. LLM models are purged separately by the caller via the model manager.
   */
  fun purgeSpeechAndSupportModels() {
    viewModelScope.launch(Dispatchers.IO) {
      ttsVoices.deleteAll()
      voskStt.delete()
      embedder.delete()
      prefs.ttsVoice = null
      prefs.sttEnabled = false
      prefs.ragEnabled = false
      _uiState.update {
        it.copy(ttsVoice = null, sttEnabled = false, ragEnabled = false, canSpeak = computeCanSpeak())
      }
      refreshModelStorage()
    }
  }

  /** Downloads an on-device voice, streaming progress into [ChatUiState]. */
  fun downloadVoice(voice: TtsVoice) {
    if (_uiState.value.downloadingVoice != null) return
    _uiState.update { it.copy(downloadingVoice = voice.locale, voiceProgress = 0f) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        ttsVoices.download(voice) { p ->
          _uiState.update { if (it.downloadingVoice == voice.locale) it.copy(voiceProgress = p) else it }
        }
        // First voice installed becomes the active one.
        if (prefs.ttsVoice == null) prefs.ttsVoice = voice.locale
      } catch (t: Throwable) {
        _uiState.update { it.copy(errorMessage = "Couldn't download ${voice.name} voice.") }
      } finally {
        _uiState.update {
          it.copy(
            downloadingVoice = null,
            voiceProgress = 0f,
            installedVoices = ttsVoices.installedLocales(),
            voicesSize = ttsVoices.sizeBytes(),
            ttsVoice = prefs.ttsVoice,
            canSpeak = computeCanSpeak(),
          )
        }
      }
    }
  }

  /** Makes [locale] the active on-device voice (null reverts to the system engine). */
  fun selectVoice(locale: String?) {
    prefs.ttsVoice = locale
    ttsVoices.stop()
    _uiState.update { it.copy(ttsVoice = locale, canSpeak = computeCanSpeak()) }
  }

  /** Removes an installed on-device voice. */
  fun deleteVoice(locale: String) {
    viewModelScope.launch(Dispatchers.IO) {
      ttsVoices.delete(locale)
      if (prefs.ttsVoice == locale) prefs.ttsVoice = null
      _uiState.update {
        it.copy(
          installedVoices = ttsVoices.installedLocales(),
          voicesSize = ttsVoices.sizeBytes(),
          ttsVoice = prefs.ttsVoice,
          canSpeak = computeCanSpeak(),
        )
      }
    }
  }

  fun setSttEnabled(enabled: Boolean) {
    prefs.sttEnabled = enabled
    _uiState.update { it.copy(sttEnabled = enabled) }
    // Bring up the on-device (GMS-free) Vosk recognizer so voice typing works on GrapheneOS, where
    // the system SpeechRecognizer is absent. Downloads a ~40MB model on first enable.
    if (enabled) viewModelScope.launch(Dispatchers.IO) {
      voskStt.prepare()
      refreshModelStorage()
    }
  }

  /** True if the on-device Vosk recognizer is loaded (vs falling back to the system one). */
  private var voiceUsingVosk = false

  /**
   * Starts voice-to-text capture. Partial words land in [ChatUiState.partialTranscript]; the final
   * transcript is delivered to [onText] (the composer fills its field with it). Caller must hold the
   * RECORD_AUDIO permission. Uses the on-device Vosk recognizer when ready, else the system one.
   */
  fun startVoiceInput(onText: (String) -> Unit) {
    tts.stop()
    _uiState.update { it.copy(partialTranscript = "") }
    val listener =
      object : SttManager.Listener {
        override fun onPartial(text: String) {
          _uiState.update { it.copy(partialTranscript = text) }
        }

        override fun onFinal(text: String) {
          _uiState.update { it.copy(listening = false, partialTranscript = "", voiceLevel = 0f) }
          if (text.isNotBlank()) onText(text)
        }

        override fun onError(message: String) {
          _uiState.update {
            it.copy(listening = false, partialTranscript = "", voiceLevel = 0f, errorMessage = message)
          }
        }

        override fun onListeningChanged(listening: Boolean) {
          _uiState.update { it.copy(listening = listening, voiceLevel = if (listening) it.voiceLevel else 0f) }
        }

        override fun onRms(level: Float) {
          _uiState.update { it.copy(voiceLevel = level) }
        }
      }
    voiceUsingVosk = voskStt.isReady()
    if (voiceUsingVosk) voskStt.start(listener) else stt.start(listener)
  }

  fun stopVoiceInput() {
    if (voiceUsingVosk) voskStt.stop() else stt.stop()
  }

  override fun onCleared() {
    super.onCleared()
    stt.destroy()
    tts.shutdown()
    ttsVoices.release()
  }

  /**
   * Restores the last-used model once, on first load. If the last selection was a cloud model,
   * resume it without ever loading a local model into RAM; otherwise fall back to the local model.
   */
  fun autoSelect(model: Model?) {
    if (autoResolved) return
    autoResolved = true
    val apiId = prefs.activeApiModelId
    val cfg = if (apiId != null) prefs.apiModels().firstOrNull { it.id == apiId } else null
    if (cfg != null) {
      setActiveApiModel(cfg)
      return
    }
    // Crash-loop breaker: a model that crashed the native engine on this device is disabled — don't
    // auto-load it (ChatEngine also refuses it, this just shows a friendlier "no model" screen).
    if (model != null && prefs.isModelPoisoned(model.name)) {
      noModel = true
      _uiState.update {
        it.copy(
          phase = ChatPhase.NO_MODEL,
          isApiModel = false,
          errorMessage =
            "\"${prefs.displayNameFor(model.name, model.displayName.ifEmpty { model.name })}\" " +
              "crashed while loading on this device. Pick a different model or a cloud model.",
        )
      }
      return
    }
    setActiveModel(model)
  }

  fun setActiveModel(model: Model?) {
    activeApi = null
    prefs.activeApiModelId = null
    if (model == null) {
      noModel = true
      _uiState.update { it.copy(phase = ChatPhase.NO_MODEL, isApiModel = false) }
      return
    }
    noModel = false
    if (engine.isReadyFor(model.name)) {
      _uiState.update {
        it.copy(
          phase = ChatPhase.READY,
          isApiModel = false,
          modelName = prefs.displayNameFor(model.name, model.displayName.ifEmpty { model.name }),
          supportsImage = model.llmSupportImage,
          supportsAudio = model.llmSupportAudio,
        )
      }
      return
    }
    loadJob =
      viewModelScope.launch(Dispatchers.Default) {
        prefs.defaultModelName = model.name
        engine.ensureLoaded(model, engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
      }
  }

  /** Frees the on-device model from memory (and aborts an in-progress load). */
  fun unloadModel() {
    loadJob?.cancel()
    loadJob = null
    noModel = true
    activeApi = null
    viewModelScope.launch(Dispatchers.Default) { engine.unload() }
    _uiState.update {
      it.copy(
        phase = ChatPhase.NO_MODEL,
        isApiModel = false,
        modelName = "",
        supportsImage = false,
        supportsAudio = false,
      )
    }
  }

  /** Adopts a transcript handed over from the assistant popup as the current (saved) chat. */
  fun adoptPopupConversation(messages: List<ChatMessage>) {
    if (messages.isEmpty()) return
    current = newConversation()
    needsTitle = false
    current.title =
      messages.firstOrNull { it.role == ChatRole.USER }?.text?.take(40)?.ifEmpty { "Chat" } ?: "Chat"
    current.messages.addAll(messages)
    current.updatedAt = System.currentTimeMillis()
    if (activeApi == null) engine.resetConversation(engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
    _uiState.update { it.copy(messages = messages.toList()) }
    if (!prefs.ghostMode) {
      store.save(current)
      _uiState.update { it.copy(conversations = store.listConversations()) }
    }
  }

  /** Switches to a remote API model, unloading the on-device model to free RAM. */
  fun setActiveApiModel(config: ApiModelConfig) {
    activeApi = config
    prefs.activeApiModelId = config.id
    noModel = false
    viewModelScope.launch(Dispatchers.Default) { engine.unload() }
    _uiState.update {
      it.copy(
        phase = ChatPhase.READY,
        isApiModel = true,
        modelName = config.displayName,
        supportsImage = config.supportsVision,
        supportsAudio = false,
      )
    }
  }

  fun send(text: String, images: List<Bitmap> = emptyList(), audioClips: List<ByteArray> = emptyList()) {
    val trimmed = text.trim()
    if ((trimmed.isEmpty() && images.isEmpty() && audioClips.isEmpty()) || _uiState.value.streaming)
      return
    if (activeApi == null && engine.conversation == null) {
      _uiState.update { it.copy(phase = ChatPhase.ERROR, errorMessage = "Model is not ready yet") }
      return
    }
    if (current.title == "New chat")
      current.title = trimmed.take(40).ifEmpty { if (audioClips.isNotEmpty()) "Voice message" else "Image" }
    // Cloud title models run concurrently with the reply; local ones wait until the engine is free
    // after the reply (handled in commitAssistant), since only one local model fits in memory.
    if (needsTitle && trimmed.isNotEmpty() && resolveTitleTarget() is TitleTarget.Cloud) generateTitle(trimmed)

    streamJob =
      viewModelScope.launch(Dispatchers.Default) {
        val imagePaths = images.map { saveImage(it) }
        val audioPath = audioClips.firstOrNull()?.let { saveAudio(it) }
        val userMsg =
          ChatMessage(role = ChatRole.USER, text = trimmed, imagePaths = imagePaths, audioPath = audioPath)
        pendingUser = userMsg
        pendingUserPersisted = false
        _uiState.update {
          it.copy(messages = it.messages + userMsg, streaming = true, streamingText = "", streamingThinking = "", errorMessage = "")
        }
        streamTurn(trimmed, images, audioClips)
      }
  }

  fun regenerate() {
    if (_uiState.value.streaming) return
    val msgs = _uiState.value.messages
    if (msgs.lastOrNull()?.role != ChatRole.ASSISTANT) return
    val lastUser = msgs.dropLast(1).lastOrNull { it.role == ChatRole.USER } ?: return
    _uiState.update { it.copy(messages = msgs.dropLast(1)) }
    if (current.messages.lastOrNull()?.role == ChatRole.ASSISTANT) {
      current.messages.removeAt(current.messages.lastIndex)
    }
    pendingUser = lastUser
    pendingUserPersisted = true
    streamJob =
      viewModelScope.launch(Dispatchers.Default) {
        _uiState.update { it.copy(streaming = true, streamingText = "", streamingThinking = "", errorMessage = "") }
        streamTurn(lastUser.text, emptyList(), emptyList())
      }
  }

  /** Rewinds to just before [message] and resends it with [newText] (inline bubble edit). */
  fun editAndResend(message: ChatMessage, newText: String) {
    if (_uiState.value.streaming) return
    val trimmed = newText.trim()
    if (trimmed.isEmpty()) return
    val msgs = _uiState.value.messages
    val idx = msgs.indexOfFirst { it.role == ChatRole.USER && it.ts == message.ts }
    if (idx < 0) return
    val kept = msgs.subList(0, idx).toList()
    while (current.messages.size > kept.size) current.messages.removeAt(current.messages.lastIndex)
    current.updatedAt = System.currentTimeMillis()
    _uiState.update { it.copy(messages = kept) }
    if (activeApi == null) engine.resetConversation(engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
    send(trimmed)
  }

  fun stopGeneration() {
    if (!_uiState.value.streaming) return
    engine.stop()
    streamJob?.cancel()
    streamJob = null
    commitAssistant(_uiState.value.streamingText, _uiState.value.streamingThinking, runActions = false)
  }

  /** Builds web-search + RAG context (if enabled) and dispatches to the local or remote backend. */
  private suspend fun streamTurn(rawUserText: String, images: List<Bitmap>, audioClips: List<ByteArray>) {
    val prefixes = mutableListOf<String>()
    pendingSources = emptyList()
    // On-device models call searchWeb on demand (native function calling). OpenAI-compatible cloud
    // models also get the tool (handled in runRemoteStream) so they only search when needed. Only
    // Anthropic/Gemini — whose wire format we don't tool-call — fall back to pre-fetched results.
    val preFetchApi = activeApi
    val preFetch =
      webSearch.enabled &&
        rawUserText.isNotBlank() &&
        preFetchApi != null &&
        (preFetchApi.provider == ApiProvider.ANTHROPIC || preFetchApi.provider == ApiProvider.GEMINI)
    if (preFetch) {
      _uiState.update { it.copy(searchingWeb = true) }
      val results = webSearch.search(rawUserText)
      _uiState.update { it.copy(searchingWeb = false) }
      pendingSources = results
      if (results.isNotEmpty()) {
        val body = results.joinToString("\n\n") { "• ${it.title}\n${it.url}\n${it.snippet}" }
        prefixes.add(
          "Web search results for \"$rawUserText\":\n$body\n\nUse these results and cite the sources."
        )
      }
    }
    // Ghost mode suspends memory: no document retrieval while it's on.
    if (prefs.ragEnabled && !prefs.ghostMode && rawUserText.isNotBlank()) {
      val r = rag.retrieve(rawUserText)
      if (r.isNotBlank()) prefixes.add(r)
    }
    val augmented =
      if (prefixes.isEmpty()) rawUserText
      else prefixes.joinToString("\n\n") + "\n\n---\nUser: " + rawUserText

    // An image with no caption still needs an instruction, or the model has nothing to answer.
    val effectiveText = augmented.ifBlank { if (images.isNotEmpty()) IMAGE_ONLY_PROMPT else "" }

    val api = activeApi
    if (api != null) {
      runRemoteStream(api, effectiveText, images)
    } else {
      val contents = mutableListOf<Content>()
      for (image in images) contents.add(Content.ImageBytes(image.toPng()))
      for (clip in audioClips) contents.add(Content.AudioBytes(clip))
      if (effectiveText.isNotEmpty()) contents.add(Content.Text(effectiveText))
      runStream(contents)
    }
  }

  private suspend fun runStream(contents: List<Content>) {
    val conversation = engine.conversation
    if (conversation == null) {
      _uiState.update {
        it.copy(streaming = false, streamingText = "", streamingThinking = "", phase = ChatPhase.ERROR, errorMessage = "Model not ready")
      }
      return
    }
    engine.clearPendingActions()
    val answer = StringBuilder()
    val thought = StringBuilder()
    conversation
      .sendMessageAsync(Contents.of(contents), mapOf("enable_thinking" to "true"))
      .catch { e ->
        if (e is CancellationException) throw e
        Log.e(TAG, "Inference failed", e)
        _uiState.update {
          it.copy(streaming = false, streamingText = "", streamingThinking = "", phase = ChatPhase.ERROR, errorMessage = e.message ?: "Error")
        }
      }
      .onCompletion { cause -> if (cause == null) commitAssistant(answer.toString(), thought.toString(), runActions = true) }
      .collect { chunk ->
        answer.append(chunk.toString())
        chunk.channels["thought"]?.let { thought.append(it) }
        _uiState.update { it.copy(streamingText = answer.toString(), streamingThinking = thought.toString()) }
      }
  }

  private suspend fun runRemoteStream(config: ApiModelConfig, augmentedUserText: String, images: List<Bitmap>) {
    // History = all current messages, with the final user message swapped for the augmented text.
    val history = _uiState.value.messages.toMutableList()
    if (history.isNotEmpty()) history[history.lastIndex] = history.last().copy(text = augmentedUserText)
    val imgBytes = if (config.supportsVision) images.map { it.toRemoteJpeg() } else emptyList()
    val answer = StringBuilder()
    val thought = StringBuilder()
    // Per-model system prompt overrides the app default when set.
    val systemPrompt = config.systemPrompt.ifBlank { prefs.effectiveSystemPrompt() }
    // Offer the web-search tool so OpenAI-compatible models search only when they decide to need it.
    val onSearch: (suspend (String) -> String)? =
      if (webSearch.enabled) {
        { query ->
          _uiState.update { it.copy(searchingWeb = true) }
          val results = webSearch.search(query)
          _uiState.update { it.copy(searchingWeb = false) }
          pendingSources = results
          if (results.isEmpty()) ""
          else results.joinToString("\n\n") { "• ${it.title}\n${it.url}\n${it.snippet}" }
        }
      } else null
    remote
      .stream(config, systemPrompt, history, imgBytes, onSearch)
      .catch { e ->
        if (e is CancellationException) throw e
        Log.e(TAG, "Remote inference failed", e)
        _uiState.update {
          it.copy(streaming = false, streamingText = "", streamingThinking = "", phase = ChatPhase.ERROR, errorMessage = e.message ?: "Error")
        }
      }
      .onCompletion { cause -> if (cause == null) commitAssistant(answer.toString(), thought.toString(), runActions = false) }
      .collect { chunk ->
        if (chunk.text.isNotEmpty()) answer.append(chunk.text)
        if (chunk.thought.isNotEmpty()) thought.append(chunk.thought)
        _uiState.update { it.copy(streamingText = answer.toString(), streamingThinking = thought.toString()) }
      }
  }

  private fun commitAssistant(rawAnswer: String, channelThinking: String, runActions: Boolean) {
    val (thinking, answerBase) = splitThinking(rawAnswer, channelThinking)
    val labels = if (runActions) engine.runPendingActions() else emptyList()
    val finalText =
      if (labels.isEmpty()) answerBase else (answerBase + "\n\n" + labels.joinToString("\n")).trim()

    if (finalText.isBlank() && thinking.isBlank()) {
      _uiState.update { it.copy(streaming = false, streamingText = "", streamingThinking = "") }
      pendingUser = null
      pendingSources = emptyList()
      return
    }

    // Cloud uses the pre-fetched sources; on-device pulls whatever the searchWeb tool captured.
    val sources = if (runActions) engine.takeWebSources() else pendingSources
    val assistantMsg =
      ChatMessage(
        role = ChatRole.ASSISTANT,
        text = finalText,
        thinking = thinking,
        sources = sources,
      )
    pendingSources = emptyList()
    _uiState.update {
      it.copy(messages = it.messages + assistantMsg, streaming = false, streamingText = "", streamingThinking = "")
    }
    pendingUser?.let { u ->
      if (!pendingUserPersisted) {
        current.messages.add(u)
        pendingUserPersisted = true
      }
    }
    current.messages.add(assistantMsg)
    current.updatedAt = System.currentTimeMillis()
    pendingUser = null
    if (!prefs.ghostMode) {
      store.save(current)
      _uiState.update { it.copy(conversations = store.listConversations()) }
    }
    // Generate the title once, after the first reply (local models are now free; cloud already fired).
    if (needsTitle) {
      val seed = current.messages.firstOrNull { it.role == ChatRole.USER }?.text.orEmpty()
      if (seed.isNotBlank()) generateTitle(seed)
    }
  }

  fun newChat() {
    current = newConversation()
    needsTitle = true
    if (activeApi == null) engine.resetConversation(engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
    _uiState.update { it.copy(messages = listOf()) }
  }

  fun loadConversation(conversation: Conversation) {
    current = conversation.copy(messages = conversation.messages.toMutableList())
    needsTitle = false
    if (activeApi == null) engine.resetConversation(engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
    _uiState.update { it.copy(messages = conversation.messages.toList()) }
  }

  /** Renames a conversation (manual override of the auto-generated title). */
  fun renameConversation(id: String, title: String) {
    val clean = title.trim().ifEmpty { return }
    if (current.id == id) {
      current.title = clean
      needsTitle = false
    }
    viewModelScope.launch(Dispatchers.IO) {
      store.listConversations().firstOrNull { it.id == id }?.let {
        it.title = clean
        store.save(it)
      }
      if (current.id == id && !prefs.ghostMode) store.save(current)
      _uiState.update { it.copy(conversations = store.listConversations()) }
    }
  }

  /**
   * Toggle the double-tap-back gesture. The caller (settings UI) is responsible for ensuring the
   * "display over other apps" grant exists before enabling, since the background launch needs it.
   */
  fun setBackTapEnabled(enabled: Boolean) {
    prefs.backTapEnabled = enabled
    _uiState.update { it.copy(backTapEnabled = enabled) }
    if (enabled) BackTapService.start(appContext) else BackTapService.stop(appContext)
  }

  /** Adjust how hard a back-tap must be (0 = firm knock, 100 = gentle); applies live if running. */
  fun setBackTapSensitivity(percent: Int) {
    val clamped = percent.coerceIn(0, 100)
    prefs.backTapSensitivity = clamped
    _uiState.update { it.copy(backTapSensitivity = clamped) }
    // Re-start nudges the running service to re-read the new threshold (no-op when off).
    if (prefs.backTapEnabled) BackTapService.start(appContext)
  }

  /**
   * Enable the "see your screen" feature. This is just a feature flag — no screen is captured here.
   * The actual one-shot capture (with its own consent) happens only when the user taps "See screen"
   * in the assistant popup, so nothing is recorded in the background.
   */
  fun setScreenReadingEnabled(enabled: Boolean) {
    prefs.screenReadingEnabled = enabled
    _uiState.update { it.copy(screenReadingEnabled = enabled) }
  }

  fun setGhostMode(enabled: Boolean) {
    prefs.ghostMode = enabled
    _uiState.update { it.copy(ghostMode = enabled) }
    // Re-seed the on-device prompt so the name switches to/from "Anonymous" right away.
    if (activeApi == null) engine.resetConversation(engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
  }

  fun setUsername(name: String) {
    prefs.username = name.trim()
    _uiState.update { it.copy(username = name.trim()) }
    if (activeApi == null) engine.resetConversation(engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
  }

  fun setSystemPrompt(prompt: String) {
    prefs.systemPrompt = prompt
    _uiState.update { it.copy(systemPrompt = prompt) }
    if (activeApi == null) engine.resetConversation(engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
  }

  /** Switches the accent color; applies live across the whole app. */
  fun setTheme(id: String) {
    prefs.theme = id
    JsonTheme.setAccent(appContext, id)
    _uiState.update { it.copy(theme = id) }
  }

  /** Switches the background mode (white / dark / beige); applies live across the whole app. */
  fun setBackgroundMode(mode: String) {
    prefs.backgroundMode = mode
    JsonTheme.setBackground(appContext, mode)
    _uiState.update { it.copy(backgroundMode = mode) }
  }

  /** Sets the app language: switches the UI live and tells the model which language to reply in. */
  fun setAppLanguage(code: String?) {
    prefs.appLanguage = code
    Strings.setLanguage(appContext, code)
    _uiState.update { it.copy(appLanguage = code) }
    // Re-seed the on-device system prompt so the language instruction takes effect immediately.
    if (activeApi == null) engine.resetConversation(engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
  }

  fun renameCurrentModel(alias: String) {
    val name = engine.model?.name ?: activeApi?.id ?: return
    prefs.setRename(name, alias)
    val fallback = _uiState.value.modelName
    _uiState.update { it.copy(modelName = if (alias.isBlank()) fallback else alias.trim()) }
  }

  // ---- API model management ----
  fun saveApiModel(config: ApiModelConfig) {
    prefs.saveApiModel(config)
    // If the edited model is the active one, refresh its live capabilities (e.g. vision).
    if (activeApi?.id == config.id) {
      activeApi = config
      _uiState.update {
        it.copy(
          apiModels = prefs.apiModels(),
          modelName = config.displayName,
          supportsImage = config.supportsVision,
        )
      }
    } else {
      _uiState.update { it.copy(apiModels = prefs.apiModels()) }
    }
  }

  fun deleteApiModel(id: String) {
    prefs.deleteApiModel(id)
    if (activeApi?.id == id) {
      activeApi = null
      prefs.activeApiModelId = null
    }
    _uiState.update { it.copy(apiModels = prefs.apiModels()) }
  }

  /** Finds the saved model config for a (credential, modelId) pair, if it's enabled. */
  private fun findApiModel(cred: ApiCredential, modelId: String): ApiModelConfig? =
    prefs.apiModels().firstOrNull {
      it.modelId == modelId && it.baseUrl == cred.baseUrl && it.apiKey == cred.apiKey
    }

  /** Whether a discovered model from [cred] is currently enabled (has a saved config). */
  fun isApiModelEnabled(cred: ApiCredential, modelId: String): Boolean =
    _uiState.value.apiModels.any {
      it.modelId == modelId && it.baseUrl == cred.baseUrl && it.apiKey == cred.apiKey
    }

  /** Bulk toggle: enabling adds a model config for the provider; disabling removes it. */
  fun setApiModelEnabled(cred: ApiCredential, modelId: String, enabled: Boolean) {
    if (!enabled) {
      findApiModel(cred, modelId)?.let { deleteApiModel(it.id) }
      return
    }
    if (findApiModel(cred, modelId) != null) return
    val config =
      ApiModelConfig(
        id = UUID.randomUUID().toString(),
        provider = cred.provider,
        displayName = modelId,
        baseUrl = cred.baseUrl,
        apiKey = cred.apiKey,
        modelId = modelId,
      )
    if (isOllama(cred.provider)) {
      // Ollama reports whether the model can see images; auto-set vision so the user needn't.
      viewModelScope.launch(Dispatchers.IO) {
        val caps = remote.ollamaCapabilities(cred, modelId)
        if (findApiModel(cred, modelId) == null) {
          saveApiModel(config.copy(supportsVision = "vision" in caps))
        }
      }
    } else {
      saveApiModel(config)
    }
  }

  private fun isOllama(provider: ApiProvider) =
    provider == ApiProvider.OLLAMA || provider == ApiProvider.OLLAMA_CLOUD

  /** Refresh vision detection for already-enabled Ollama models of [cred] (e.g. added before this). */
  private fun refreshOllamaCapabilities(cred: ApiCredential) {
    if (!isOllama(cred.provider)) return
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.value.apiModels
        .filter { it.baseUrl == cred.baseUrl && it.apiKey == cred.apiKey }
        .forEach { model ->
          val vision = "vision" in remote.ollamaCapabilities(cred, model.modelId)
          if (vision != model.supportsVision) saveApiModel(model.copy(supportsVision = vision))
        }
    }
  }

  /** Auto mode: make sure every model the endpoint listed has an enabled config for [cred]. */
  fun enableAllApiModels(cred: ApiCredential, modelIds: List<String>) {
    modelIds.forEach { id -> if (findApiModel(cred, id) == null) setApiModelEnabled(cred, id, true) }
  }

  fun saveApiCredential(cred: ApiCredential) {
    prefs.saveApiCredential(cred)
    _uiState.update { it.copy(apiCredentials = prefs.apiCredentials()) }
  }

  fun deleteApiCredential(id: String) {
    prefs.deleteApiCredential(id)
    _uiState.update { it.copy(apiCredentials = prefs.apiCredentials()) }
  }

  // ---- Web search ----
  fun setWebSearchProvider(p: String) {
    prefs.webSearchProvider = p
    _uiState.update { it.copy(webSearchProvider = p) }
  }

  fun setSearxngUrl(url: String) {
    prefs.searxngUrl = url
    _uiState.update { it.copy(searxngUrl = url) }
  }

  fun setOllamaSearchKey(key: String) {
    prefs.ollamaSearchKey = key
    _uiState.update { it.copy(ollamaSearchKey = key) }
  }

  // ---- RAG ----
  fun setRagEnabled(enabled: Boolean) {
    prefs.ragEnabled = enabled
    _uiState.update { it.copy(ragEnabled = enabled) }
    // Bring up the on-device embedder for semantic retrieval (downloads ~55MB on first enable).
    if (enabled) viewModelScope.launch(Dispatchers.IO) {
      embedder.prepare()
      refreshModelStorage()
    }
  }

  fun addRagDocument(title: String, text: String) {
    viewModelScope.launch(Dispatchers.IO) {
      rag.addDocument(title, text)
      _uiState.update { it.copy(ragDocs = rag.listDocuments()) }
    }
  }

  fun deleteRagDocument(id: String) {
    viewModelScope.launch(Dispatchers.IO) {
      rag.deleteDocument(id)
      _uiState.update { it.copy(ragDocs = rag.listDocuments()) }
    }
  }

  fun deleteConversation(id: String) {
    if (current.id == id) newChat()
    viewModelScope.launch(Dispatchers.IO) {
      store.delete(id)
      val conversations = store.listConversations()
      _uiState.update { it.copy(conversations = conversations) }
    }
  }

  fun clearAllData() {
    newChat()
    viewModelScope.launch(Dispatchers.IO) {
      store.deleteAll()
      _uiState.update { it.copy(conversations = listOf()) }
    }
  }

  /**
   * Runs a one-shot prompt (optionally with audio) for the tool pages (Transcribe, Translate),
   * streaming into [toolState] without touching the chat history.
   */
  fun runTool(prompt: String, audio: ByteArray? = null) {
    toolJob?.cancel()
    _toolState.value = ToolUiState(running = true)
    toolJob =
      viewModelScope.launch(Dispatchers.Default) {
        val api = activeApi
        val out = StringBuilder()
        try {
          if (api != null) {
            if (audio != null) {
              _toolState.value =
                ToolUiState(error = "Audio transcription needs an on-device model with audio support.")
              return@launch
            }
            remote
              .stream(api, prefs.effectiveSystemPrompt(), listOf(ChatMessage(ChatRole.USER, prompt)), emptyList())
              .collect { c ->
                if (c.text.isNotEmpty()) {
                  out.append(c.text)
                  _toolState.update { it.copy(output = out.toString()) }
                }
              }
          } else {
            val conv = engine.createDetachedConversation(Contents.of("You are a precise, literal assistant."))
            if (conv == null) {
              _toolState.value = ToolUiState(error = "Model not ready. Load a model or pick a cloud one.")
              return@launch
            }
            if (audio != null && !engine.state.value.supportsAudio) {
              _toolState.value =
                ToolUiState(error = "This model can't process audio. Load a Gemma model with audio support.")
              return@launch
            }
            val contents = mutableListOf<Content>()
            if (audio != null) contents.add(Content.AudioBytes(audio))
            contents.add(Content.Text(prompt))
            conv.sendMessageAsync(Contents.of(contents)).collect { chunk ->
              out.append(chunk.toString())
              _toolState.update { it.copy(output = out.toString()) }
            }
            try {
              conv.close()
            } catch (_: Exception) {}
          }
          _toolState.update { it.copy(running = false, output = out.toString()) }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Tool run failed", e)
          _toolState.value = ToolUiState(error = e.message ?: "Error")
        }
      }
  }

  fun clearTool() {
    toolJob?.cancel()
    toolJob = null
    _toolState.value = ToolUiState()
  }

  // ---- MCP servers + skills ----

  /** Ensures both catalogs are warmed (no-op if the engine already loaded them). */
  fun warmTools() {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching { skillManager.load() }
      runCatching { mcpManager.load() }
    }
  }

  /** Connects an MCP server, optionally with a single request-header auth. */
  fun addMcpServer(url: String, authHeaderName: String? = null, authHeaderValue: String? = null) {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return
    val auth =
      if (!authHeaderName.isNullOrBlank() && !authHeaderValue.isNullOrBlank()) {
        McpAuth.newBuilder()
          .setRequestHeader(
            McpAuth.RequestHeader.newBuilder()
              .setHeaderName(authHeaderName.trim())
              .setHeaderValue(authHeaderValue.trim())
          )
          .build()
      } else {
        McpAuth.newBuilder().setNone(true).build()
      }
    mcpManager.addServer(trimmed, auth)
  }

  fun removeMcpServer(url: String) = mcpManager.removeServer(url)

  fun setMcpServerEnabled(url: String, enabled: Boolean) = mcpManager.setServerEnabled(url, enabled)

  fun setMcpToolEnabled(url: String, toolName: String, enabled: Boolean) =
    mcpManager.setToolEnabled(url, toolName, enabled)

  fun setMcpToolAlwaysAllow(url: String, toolName: String, alwaysAllow: Boolean) =
    mcpManager.setToolAlwaysAllow(url, toolName, alwaysAllow)

  fun setSkillSelected(skill: Skill, selected: Boolean) =
    skillManager.setSkillSelected(skill, selected)

  /** Resolves the on-screen tool-call confirmation; "always allow" also persists the tool flag. */
  fun resolvePermission(decision: PermissionDecision) {
    val request = permissionRequest.value ?: return
    if (decision == PermissionDecision.ALWAYS_ALLOW) {
      mcpState.value.mcpServers
        .firstOrNull { st -> st.mcpServer.toolsList.any { it.name == request.toolName } }
        ?.let { mcpManager.setToolAlwaysAllow(it.mcpServer.url, request.toolName, true) }
    }
    request.resolve(decision)
  }

  fun preferredModelName(): String? = prefs.defaultModelName

  // ---- Default-model config (popup + chat titles) ----

  fun setPopupModel(tag: String?) {
    prefs.popupModel = tag
    _uiState.update { it.copy(popupModel = tag) }
  }

  fun setTitleModel(tag: String?) {
    prefs.titleModel = tag
    _uiState.update { it.copy(titleModel = tag) }
  }

  /** Where a title comes from, given the [ChatPrefs.titleModel] setting and the current chat. */
  private sealed interface TitleTarget {
    data class Cloud(val config: ApiModelConfig) : TitleTarget
    /** Use the local model already warm in the engine. */
    object LocalLoaded : TitleTarget
    object None : TitleTarget
  }

  private fun resolveTitleTarget(): TitleTarget {
    val tag = prefs.titleModel
    // Explicit cloud pick — runs concurrently regardless of the chat model.
    if (tag != null && tag.startsWith("api:")) {
      val id = tag.removePrefix("api:")
      prefs.apiModels().firstOrNull { it.id == id }?.let { return TitleTarget.Cloud(it) }
    }
    // Explicit local pick only works if that model is the one currently warm (one local model fits).
    if (tag != null && tag.startsWith("local:")) {
      val name = tag.removePrefix("local:")
      if (activeApi == null && engine.isReadyFor(name)) return TitleTarget.LocalLoaded
    }
    // "Same as chat" (or a pick that can't run right now): use whatever is active.
    return when {
      activeApi != null -> TitleTarget.Cloud(activeApi!!)
      engine.conversation != null -> TitleTarget.LocalLoaded
      else -> TitleTarget.None
    }
  }

  /** Kicks off a one-off title generation for the current conversation, if it still needs one. */
  private fun generateTitle(seed: String) {
    if (!needsTitle) return
    val target = resolveTitleTarget()
    if (target is TitleTarget.None) return
    needsTitle = false
    val convId = current.id
    val prompt =
      "Write a short title (3 to 5 words, no quotes, no punctuation at the end) for a chat that " +
        "starts with this message:\n\n${seed.take(500)}"
    viewModelScope.launch(Dispatchers.IO) {
      val raw =
        try {
          when (target) {
            is TitleTarget.Cloud -> {
              val sb = StringBuilder()
              remote
                .stream(target.config, TITLE_SYSTEM_PROMPT, listOf(ChatMessage(ChatRole.USER, prompt)), emptyList())
                .collect { if (it.text.isNotEmpty()) sb.append(it.text) }
              sb.toString()
            }
            TitleTarget.LocalLoaded -> {
              val conv = engine.createDetachedConversation(Contents.of(TITLE_SYSTEM_PROMPT)) ?: return@launch
              val sb = StringBuilder()
              conv.sendMessageAsync(Contents.of(listOf(Content.Text(prompt)))).collect { sb.append(it.toString()) }
              try {
                conv.close()
              } catch (_: Exception) {}
              sb.toString()
            }
            TitleTarget.None -> return@launch
          }
        } catch (e: Exception) {
          Log.w(TAG, "Title generation failed", e)
          return@launch
        }
      val title = cleanTitle(raw)
      if (title.isNotEmpty()) applyTitle(convId, title)
    }
  }

  private fun cleanTitle(raw: String): String =
    raw
      .substringBefore('\n')
      .replace(Regex("</?think>|<[^>]+>"), "")
      .trim()
      .trim('"', '\'', '.', '*', '#', ' ')
      .take(48)

  private fun applyTitle(convId: String, title: String) {
    if (current.id == convId) current.title = title
    if (!prefs.ghostMode) {
      store.listConversations().firstOrNull { it.id == convId }?.let {
        it.title = title
        store.save(it)
      }
      if (current.id == convId) store.save(current)
    }
    _uiState.update { it.copy(conversations = store.listConversations()) }
  }

  private fun Bitmap.toPng(): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }

  /**
   * Downscaled JPEG for remote vision APIs. A full-res photo as base64 PNG is several MB, which
   * stalls or is rejected by some endpoints (e.g. Ollama Cloud) — this keeps it small and reliable.
   */
  private fun Bitmap.toRemoteJpeg(maxDim: Int = 1280, quality: Int = 85): ByteArray {
    val longest = maxOf(width, height)
    val scaled =
      if (longest > maxDim) {
        val s = maxDim.toFloat() / longest
        Bitmap.createScaledBitmap(this, (width * s).toInt(), (height * s).toInt(), true)
      } else this
    val stream = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
    if (scaled !== this) scaled.recycle()
    return stream.toByteArray()
  }

  private fun saveImage(bitmap: Bitmap): String {
    val dir = java.io.File(appContext.filesDir, "chat_images").apply { mkdirs() }
    val file = java.io.File(dir, "${UUID.randomUUID()}.jpg")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    return file.absolutePath
  }

  private fun saveAudio(bytes: ByteArray): String {
    val dir = java.io.File(appContext.filesDir, "chat_audio").apply { mkdirs() }
    val file = java.io.File(dir, "${UUID.randomUUID()}.wav")
    file.outputStream().use { it.write(bytes) }
    return file.absolutePath
  }
}
