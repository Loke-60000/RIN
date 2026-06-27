// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaiwa.chat.ApiModelConfig
import app.kaiwa.chat.ChatEngine
import app.kaiwa.chat.ChatMessage
import app.kaiwa.chat.ChatPrefs
import app.kaiwa.chat.ChatRole
import app.kaiwa.chat.RemoteChatClient
import app.kaiwa.data.Model
import app.kaiwa.speech.SttManager
import app.kaiwa.speech.TtsManager
import app.kaiwa.speech.VoskStt
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGAssistantVM"

enum class AssistantPhase {
  INITIALIZING,
  NO_MODEL,
  READY,
  THINKING,
  DONE,
  ERROR,
}

data class AssistantUiState(
  val phase: AssistantPhase = AssistantPhase.INITIALIZING,
  val modelName: String = "",
  val lastPrompt: String = "",
  val response: String = "",
  val performedActions: List<String> = listOf(),
  val errorMessage: String = "",
  val hasScreenContext: Boolean = false,
  val modelSupportsVision: Boolean = false,
  /** Whether the "see your screen" feature is enabled in settings (gates the popup's chip). */
  val screenReadingEnabled: Boolean = false,
  /** True while the mic is capturing speech. */
  val listening: Boolean = false,
  /** Live transcript shown in the input while the user is speaking. */
  val partialTranscript: String = "",
  /** When on, the assistant reads its replies aloud (the hands-free voice loop). */
  val voiceReplies: Boolean = false,
)

@HiltViewModel
class AssistantViewModel
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
  private val engine: ChatEngine,
  private val voskStt: VoskStt,
) : ViewModel() {

  private val prefs = ChatPrefs(appContext)
  private val remote = RemoteChatClient()
  private val tts = TtsManager(appContext)
  private val stt = SttManager(appContext)
  private val _uiState = MutableStateFlow(AssistantUiState(screenReadingEnabled = prefs.screenReadingEnabled))
  val uiState = _uiState.asStateFlow()

  private var prepared = false
  private var apiConfig: ApiModelConfig? = null
  /** Set when the turn was started by voice, so we speak the reply back even if voiceReplies is off. */
  private var spokenTurn = false

  // The popup runs an isolated, ephemeral conversation so it never disturbs the on-screen chat.
  private var localConversation: Conversation? = null
  private var localIsDetached = false
  // The full popup transcript, published to the app only if the user taps "Open Rin".
  private val transcript = mutableListOf<ChatMessage>()

  /** Reuses the warm shared engine if loaded; otherwise loads the model. Runs at most once. */
  fun prepare(model: Model?) {
    if (prepared) return
    prepared = true

    // The popup uses its configured cloud model if set (e.g. a fast cloud model while chat is local);
    // otherwise it follows the active chat selection. A cloud model needs no loading.
    val popupTag = prefs.popupModel
    val cfg =
      if (popupTag != null && popupTag.startsWith("api:"))
        prefs.apiModels().firstOrNull { it.id == popupTag.removePrefix("api:") }
      else prefs.activeApiModelId?.let { id -> prefs.apiModels().firstOrNull { it.id == id } }
    if (cfg != null) {
      apiConfig = cfg
      _uiState.update {
        it.copy(
          phase = AssistantPhase.READY,
          modelName = cfg.displayName,
          modelSupportsVision = cfg.supportsVision,
          hasScreenContext = !ScreenContextHolder.screenText.isNullOrBlank() || ScreenContextHolder.screenshot != null,
        )
      }
      return
    }

    if (model == null) {
      _uiState.update { it.copy(phase = AssistantPhase.NO_MODEL) }
      return
    }
    _uiState.update {
      it.copy(
        phase = AssistantPhase.INITIALIZING,
        modelName = model.displayName.ifEmpty { model.name },
        hasScreenContext = !ScreenContextHolder.screenText.isNullOrBlank() || ScreenContextHolder.screenshot != null,
      )
    }

    viewModelScope.launch(Dispatchers.Default) {
      // ensureLoaded returns immediately if the model is already warm from the chat screen.
      engine.ensureLoaded(model, engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
      if (engine.isReadyFor(model.name)) {
        // Isolated, fresh conversation for this popup invocation — leaves the chat's untouched.
        val detached = engine.createDetachedConversation(engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
        if (detached != null) {
          localConversation = detached
          localIsDetached = true
        } else {
          // Fallback: no detached conversation available — reuse the shared one.
          engine.resetConversation(engine.buildSystemPrompt(prefs.effectiveSystemPrompt()))
          localConversation = engine.conversation
          localIsDetached = false
        }
        _uiState.update {
          it.copy(phase = AssistantPhase.READY, modelSupportsVision = engine.state.value.supportsImage)
        }
      } else {
        _uiState.update {
          it.copy(phase = AssistantPhase.ERROR, errorMessage = engine.state.value.error.ifEmpty { "Init failed" })
        }
      }
    }
  }

  fun ask(userPrompt: String, includeScreenContext: Boolean, userImages: List<ByteArray> = emptyList()) {
    val trimmed = userPrompt.trim()
    if (trimmed.isEmpty() && userImages.isEmpty()) return
    val cfg = apiConfig
    if (cfg == null && localConversation == null) {
      _uiState.update { it.copy(phase = AssistantPhase.ERROR, errorMessage = "Model is not ready yet") }
      return
    }

    transcript.add(ChatMessage(ChatRole.USER, trimmed))
    if (cfg == null) engine.clearPendingActions()
    _uiState.update {
      it.copy(
        phase = AssistantPhase.THINKING,
        lastPrompt = trimmed,
        response = "",
        performedActions = listOf(),
        errorMessage = "",
      )
    }

    // Screen reading is gated to vision models and is opt-in (the popup's "See screen" button).
    val useScreen = includeScreenContext && _uiState.value.modelSupportsVision
    val screenText = ScreenContextHolder.screenText
    val screenshot = ScreenContextHolder.screenshot
    val now = LocalDateTime.now()
    val dateLine =
      "Current date and time: ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))}."
    val messageText =
      if (useScreen && !screenText.isNullOrBlank()) {
        "$dateLine\nText currently on the user's screen:\n\"\"\"\n$screenText\n\"\"\"\n\nUser: $trimmed"
      } else {
        "$dateLine\n\nUser: $trimmed"
      }

    // User-attached images (the "+" button) plus the screen capture when opted in.
    val attachments = buildList {
      addAll(userImages)
      if (useScreen && screenshot != null) add(screenshot)
    }

    if (cfg != null) {
      // Cloud model.
      val images = attachments
      viewModelScope.launch(Dispatchers.Default) {
        val builder = StringBuilder()
        remote
          .stream(cfg, prefs.effectiveSystemPrompt(), listOf(ChatMessage(ChatRole.USER, messageText)), images)
          .catch { e ->
            Log.e(TAG, "Remote inference failed", e)
            _uiState.update { it.copy(phase = AssistantPhase.ERROR, errorMessage = e.message ?: "Unknown error") }
          }
          .onCompletion { cause ->
            if (cause == null) {
              transcript.add(ChatMessage(ChatRole.ASSISTANT, builder.toString()))
              _uiState.update { it.copy(response = builder.toString(), phase = AssistantPhase.DONE) }
              maybeSpeak(builder.toString())
            }
          }
          .collect { chunk ->
            if (chunk.text.isNotEmpty()) builder.append(chunk.text)
            _uiState.update { it.copy(response = builder.toString()) }
          }
      }
      return
    }

    // On-device model — uses the popup's isolated conversation.
    val conversation = localConversation ?: return
    val contents = mutableListOf<Content>()
    attachments.forEach { contents.add(Content.ImageBytes(it)) }
    contents.add(Content.Text(messageText))
    viewModelScope.launch(Dispatchers.Default) {
      val builder = StringBuilder()
      conversation
        .sendMessageAsync(Contents.of(contents))
        .catch { e ->
          Log.e(TAG, "Inference failed", e)
          _uiState.update {
            it.copy(phase = AssistantPhase.ERROR, errorMessage = e.message ?: "Unknown error")
          }
        }
        .onCompletion { cause ->
          if (cause == null) {
            val labels = engine.runPendingActions()
            transcript.add(ChatMessage(ChatRole.ASSISTANT, builder.toString()))
            _uiState.update {
              it.copy(response = builder.toString(), phase = AssistantPhase.DONE, performedActions = labels)
            }
            maybeSpeak(builder.toString())
          }
        }
        .collect { chunk ->
          builder.append(chunk.toString())
          _uiState.update { it.copy(response = builder.toString()) }
        }
    }
  }

  /** Speaks the reply aloud when voice replies are on, or when the turn was started by voice. */
  private fun maybeSpeak(text: String) {
    if (_uiState.value.voiceReplies || spokenTurn) tts.speak(text)
    spokenTurn = false
  }

  fun toggleVoiceReplies() {
    val on = !_uiState.value.voiceReplies
    if (!on) tts.stop()
    _uiState.update { it.copy(voiceReplies = on) }
  }

  fun stopSpeaking() {
    tts.stop()
  }

  /**
   * Starts capturing speech. Live words land in [AssistantUiState.partialTranscript]; when the user
   * stops, the final transcript is sent to the model and the reply is spoken back. Caller must hold
   * the RECORD_AUDIO permission.
   */
  private var voiceUsingVosk = false

  fun startVoiceInput(includeScreenContext: Boolean) {
    tts.stop()
    _uiState.update { it.copy(partialTranscript = "") }
    val listener =
      object : SttManager.Listener {
        override fun onPartial(text: String) {
          _uiState.update { it.copy(partialTranscript = text) }
        }

        override fun onFinal(text: String) {
          _uiState.update { it.copy(listening = false, partialTranscript = "") }
          if (text.isNotBlank()) {
            spokenTurn = true
            ask(text, includeScreenContext)
          }
        }

        override fun onError(message: String) {
          _uiState.update { it.copy(listening = false, partialTranscript = "", errorMessage = message) }
        }

        override fun onListeningChanged(listening: Boolean) {
          _uiState.update { it.copy(listening = listening) }
        }
      }
    // On-device Vosk when ready (GrapheneOS-safe); else the system recognizer.
    voiceUsingVosk = voskStt.isReady()
    if (voiceUsingVosk) voskStt.start(listener) else stt.start(listener)
  }

  fun stopVoiceInput() {
    if (voiceUsingVosk) voskStt.stop() else stt.stop()
  }

  /** Publishes the popup transcript to the app so the chat screen can adopt and save it. */
  fun publishHandoff() {
    PopupHandoff.publish(transcript)
  }

  override fun onCleared() {
    super.onCleared()
    // Keep the model warm in the shared engine, but free the popup's isolated conversation.
    if (localIsDetached) {
      try {
        localConversation?.close()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to close detached conversation", e)
      }
    }
    localConversation = null
    stt.destroy()
    tts.shutdown()
    ScreenContextHolder.clear()
  }
}
