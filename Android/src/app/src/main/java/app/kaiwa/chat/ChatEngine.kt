// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import app.kaiwa.chat.tools.McpManager
import app.kaiwa.chat.tools.McpToolSet
import app.kaiwa.chat.tools.PermissionGate
import app.kaiwa.chat.tools.SkillManager
import app.kaiwa.chat.tools.SkillToolSet
import app.kaiwa.customtasks.mobileactions.Action
import app.kaiwa.customtasks.mobileactions.MobileActionExecutor
import app.kaiwa.customtasks.mobileactions.MobileActionsTools
import app.kaiwa.data.Accelerator
import app.kaiwa.data.BuiltInTaskId
import app.kaiwa.data.ConfigKeys
import app.kaiwa.data.Model
import app.kaiwa.ui.llmchat.LlmChatModelHelper
import app.kaiwa.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGChatEngine"

/**
 * App-scoped holder for the loaded Gemma model. The engine is loaded once and kept in memory for
 * the lifetime of the process (never cleaned up on ViewModel clear), so leaving the chat screen or
 * triggering the assistant popup reuses the same warm model instead of reloading it.
 *
 * Image/audio backends are enabled automatically for models that declare those capabilities. The
 * device-action tools are always registered, so both the chat and the popup can run phone actions.
 */
@Singleton
class ChatEngine
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val mcpManager: McpManager,
  private val skillManager: SkillManager,
  private val permissionGate: PermissionGate,
) {
  enum class Status {
    IDLE,
    LOADING,
    READY,
    ERROR,
  }

  data class State(
    val status: Status = Status.IDLE,
    val modelName: String = "",
    val supportsImage: Boolean = false,
    val supportsAudio: Boolean = false,
    val error: String = "",
  )

  private val _state = MutableStateFlow(State())
  val state = _state.asStateFlow()

  var model: Model? = null
    private set

  private val prefs = ChatPrefs(context)
  private val pendingActions = mutableListOf<Action>()
  private val webSearch = WebSearchEngine(prefs)
  private val pendingWebSources = mutableListOf<SearchResult>()
  private val tools =
    listOf(
      tool(MobileActionsTools(onFunctionCalled = { pendingActions.add(it) })),
      tool(
        WebSearchTools(
          webSearch = webSearch,
          onResults = {
            pendingWebSources.clear()
            pendingWebSources.addAll(it)
          },
        )
      ),
      // MCP + skills tool-calling. The injected gate surfaces a confirmation dialog (UiPermissionGate)
      // and blocks the tool-calling thread until the user answers.
      tool(McpToolSet(mcpManager = mcpManager, permissionGate = permissionGate)),
      tool(SkillToolSet(skillManager = skillManager)),
    )

  // App-scoped loop that warms the MCP connections and skill catalog off the main thread, so their
  // schemas are ready by the time a system prompt is assembled. Owned by the singleton engine.
  private val toolsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  init {
    toolsScope.launch {
      runCatching { skillManager.load() }.onFailure { Log.w(TAG, "skillManager.load() failed", it) }
      runCatching { mcpManager.load() }.onFailure { Log.w(TAG, "mcpManager.load() failed", it) }
    }

    // A model can be mid-load when the process dies. Only penalise it if the process actually died
    // from a NATIVE CRASH — a user force-stop or system kill during the (slow) GPU load must not
    // demote a perfectly good model to CPU. We read the real exit reason to tell them apart.
    val nativeCrash = lastExitWasNativeCrash()
    prefs.loadingModel?.let { crashed ->
      when {
        !nativeCrash ->
          Log.i(TAG, "Process exited mid-load of '$crashed' without a native crash — not penalising it")
        prefs.loadingBackend == Accelerator.CPU.label -> {
          Log.w(TAG, "Model '$crashed' crashed even on CPU — disabling it on this device")
          prefs.addPoisonedModel(crashed)
        }
        else -> {
          Log.w(TAG, "Model '$crashed' crashed on GPU — forcing CPU backend next time")
          prefs.addForceCpuModel(crashed)
        }
      }
      prefs.loadingModel = null
      prefs.loadingBackend = null
    }
    // On any clean start, drop soft CPU demotions so GPU is retried — this heals false positives
    // (e.g. a kill during a long GPU load). Genuinely unloadable models stay poisoned separately.
    if (!nativeCrash) prefs.clearForceCpuModels()
  }

  /** Whether the app's previous process exited from a native (or JVM) crash, vs a kill/force-stop. */
  private fun lastExitWasNativeCrash(): Boolean =
    try {
      val am = context.getSystemService(ActivityManager::class.java)
      val reason = am?.getHistoricalProcessExitReasons(context.packageName, 0, 1)?.firstOrNull()?.reason
      reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_CRASH
    } catch (e: Exception) {
      false
    }

  /** Returns and clears the sources from the model's last web-search tool call (for source pills). */
  fun takeWebSources(): List<SearchResult> {
    val copy = pendingWebSources.toList()
    pendingWebSources.clear()
    return copy
  }

  val conversation: Conversation?
    get() = (model?.instance as? LlmModelInstance)?.conversation

  fun isReadyFor(modelName: String): Boolean =
    model?.name == modelName &&
      model?.instance != null &&
      _state.value.status == Status.READY

  /**
   * Builds the model-facing system prompt: the user's [base] prompt followed by the schemas of the
   * currently enabled MCP tools and selected skills. This is the one place tool schemas enter the
   * prompt, so every entry point (chat, popup, reset) stays consistent.
   */
  fun buildSystemPrompt(base: String): Contents {
    val toolsPrompt = mcpManager.getToolsPrompt()
    val skillsSummary = skillManager.getSelectedSkillsSummary()
    val text =
      buildString {
        append(base)
        if (skillsSummary.isNotBlank()) {
          append("\n\n--- AVAILABLE SKILLS ---\n")
          append("Load a skill with the load_skill tool, then follow its instructions.\n\n")
          append(skillsSummary)
        }
        if (toolsPrompt.isNotBlank()) {
          append("\n\n--- AVAILABLE MCP TOOLS ---\n")
          append("Call a tool with the run_mcp_tool tool, passing its exact name and a JSON input.\n\n")
          append(toolsPrompt)
        }
      }
    return Contents.of(text)
  }

  /** Loads [model] if not already loaded. Blocking — call from a background dispatcher. */
  @Synchronized
  fun ensureLoaded(model: Model, systemPrompt: Contents) {
    if (isReadyFor(model.name)) return

    // Chokepoint guard #1: a model that previously crashed the native engine on this device is
    // disabled — refuse it here so NO caller (autoSelect, model picker, redirect, popup) can crash.
    if (prefs.isModelPoisoned(model.name)) {
      Log.w(TAG, "Refusing to load poisoned model '${model.name}'")
      // E4B crashes on first inference on Tensor G5 (LiteRT-LM #2566); its E2B sibling runs fine and
      // has the same vision/audio/tool capabilities — point the user straight at it.
      val hint =
        if (model.name.contains("E4B", ignoreCase = true)) {
          val e2b = model.name.replace("E4B", "E2B", ignoreCase = true)
          "This model crashes the on-device engine on this phone. Try \"$e2b\" instead — it's the " +
            "smaller sibling with the same image, audio and tool support, and it runs here."
        } else {
          "This model crashed the on-device engine on this phone, so it's disabled here. " +
            "Use a cloud model or a different on-device model."
        }
      _state.update {
        State(
          status = Status.ERROR,
          modelName = model.displayName.ifEmpty { model.name },
          error = hint,
        )
      }
      return
    }

    // Chokepoint guard #2: never hand a not-yet-downloaded model to the engine — it segfaults.
    val path = runCatching { model.getPath(context) }.getOrNull()
    if (path.isNullOrBlank() || !java.io.File(path).exists()) {
      Log.w(TAG, "Model file missing for '${model.name}' at '$path'")
      _state.update {
        State(
          status = Status.ERROR,
          modelName = model.displayName.ifEmpty { model.name },
          error = "This model isn't downloaded yet. Download it in model management first.",
        )
      }
      return
    }

    // A different model is loaded — fully free it (engine + conversation) before loading the new
    // one so we never hold two models in memory at once.
    this.model?.let { old ->
      if (old.name != model.name) {
        try {
          LlmChatModelHelper.cleanUp(model = old, onDone = {})
        } catch (e: Exception) {
          Log.w(TAG, "Failed to clean up previous model", e)
        }
        this.model = null
        System.gc()
      }
    }

    // If this model's engine crashed on its default backend before, retry on CPU (some models'
    // GPU path crashes on certain devices). Image/audio backends are GPU-bound, so CPU = text-only.
    val forceCpu = prefs.isModelForceCpu(model.name)
    if (forceCpu) {
      model.configValues =
        model.configValues + (ConfigKeys.ACCELERATOR.label to Accelerator.CPU.label)
    } else {
      // Default to GPU for on-device acceleration. A stale persisted "CPU" config (e.g. written by
      // the crash-guard in a past session) must not pin us to slow CPU inference — if GPU genuinely
      // crashes, the crash-guard below force-CPUs it on the next launch.
      model.configValues =
        model.configValues + (ConfigKeys.ACCELERATOR.label to Accelerator.GPU.label)
    }
    val supportImage = model.llmSupportImage && !forceCpu
    val supportAudio = model.llmSupportAudio && !forceCpu
    val backend =
      if (forceCpu) Accelerator.CPU.label
      else model.getStringConfigValue(ConfigKeys.ACCELERATOR, Accelerator.GPU.label)
    Log.i(
      TAG,
      "Preparing '${model.name}': forceCpu=$forceCpu, configAccel='${model.getStringConfigValue(ConfigKeys.ACCELERATOR, "<unset>")}', backend=$backend, poisoned=${prefs.isModelPoisoned(model.name)}",
    )

    _state.update {
      State(
        status = Status.LOADING,
        modelName = model.displayName.ifEmpty { model.name },
        supportsImage = supportImage,
        supportsAudio = supportAudio,
      )
    }

    // Record the model + backend before the uncatchable native call. If it segfaults, these survive
    // the crash so the engine can attribute it (GPU → retry CPU, CPU → disable) on next start.
    prefs.loadingModel = model.name
    prefs.loadingBackend = backend
    LlmChatModelHelper.initialize(
      context = context,
      model = model,
      taskId = BuiltInTaskId.LLM_CHAT,
      supportImage = supportImage,
      supportAudio = supportAudio,
      systemInstruction = systemPrompt,
      tools = tools,
      onDone = { error ->
        // The native call returned (no crash) — clear the markers.
        prefs.loadingModel = null
        prefs.loadingBackend = null
        Log.i(TAG, "Load result for '${model.name}' on $backend: ${if (error.isEmpty() && model.instance != null) "READY" else "ERROR: ${error.ifEmpty { "no instance" }}"}")
        if (error.isEmpty() && model.instance != null) {
          this.model = model
          _state.update {
            it.copy(status = Status.READY, supportsImage = supportImage, supportsAudio = supportAudio)
          }
        } else {
          _state.update { it.copy(status = Status.ERROR, error = error.ifEmpty { "Init failed" }) }
        }
      },
    )
  }

  /**
   * Creates an isolated conversation on the warm engine for the assistant popup, so the popup's
   * ephemeral chat never disturbs the on-screen chat's conversation. Null if no model is loaded.
   */
  fun createDetachedConversation(systemPrompt: Contents): Conversation? =
    model?.let {
      try {
        LlmChatModelHelper.createDetachedConversation(it, systemPrompt, tools)
      } catch (e: Exception) {
        Log.w(TAG, "createDetachedConversation failed", e)
        null
      }
    }

  /** Starts a fresh conversation on the warm engine, applying the (possibly updated) prompt. */
  fun resetConversation(systemPrompt: Contents) {
    val m = model ?: return
    LlmChatModelHelper.resetConversation(
      model = m,
      supportImage = m.llmSupportImage,
      supportAudio = m.llmSupportAudio,
      systemInstruction = systemPrompt,
      tools = tools,
    )
  }

  /** Fully unloads the current model from memory (e.g. when switching to a remote API model). */
  @Synchronized
  fun unload() {
    val old = model ?: return
    try {
      LlmChatModelHelper.cleanUp(model = old, onDone = {})
    } catch (e: Exception) {
      Log.w(TAG, "unload failed", e)
    }
    model = null
    _state.update { State(status = Status.IDLE) }
    System.gc()
  }

  /** Cancels the current token generation (if any). */
  fun stop() {
    model?.let {
      try {
        LlmChatModelHelper.stopResponse(it)
      } catch (e: Exception) {
        Log.w(TAG, "stop() failed", e)
      }
    }
  }

  fun clearPendingActions() = pendingActions.clear()

  /** Runs and clears any device actions the model requested during the last turn. */
  fun runPendingActions(): List<String> {
    val labels = mutableListOf<String>()
    for (action in pendingActions) {
      val error = MobileActionExecutor.perform(action = action, context = context)
      val name = action.functionCallDetails.functionName
      labels.add(if (error.isEmpty()) "Done: $name" else "Failed: $name ($error)")
    }
    pendingActions.clear()
    return labels
  }
}
