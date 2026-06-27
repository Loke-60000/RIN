// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import app.kaiwa.common.cleanUpMediapipeTaskErrorMessage
import app.kaiwa.data.Accelerator
import app.kaiwa.data.ConfigKeys
import app.kaiwa.data.DEFAULT_MAX_TOKEN
import app.kaiwa.data.DEFAULT_TEMPERATURE
import app.kaiwa.data.DEFAULT_TOPK
import app.kaiwa.data.DEFAULT_TOPP
import app.kaiwa.data.DEFAULT_VISION_ACCELERATOR
import app.kaiwa.data.Model
import app.kaiwa.data.ModelCapability
import app.kaiwa.runtime.CleanUpListener
import app.kaiwa.runtime.LlmModelHelper
import app.kaiwa.runtime.ResultListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AGLlmChatModelHelper"

data class LlmModelInstance(val engine: Engine, var conversation: Conversation)

/** [LlmModelHelper] backed by the on-device LiteRT-LM engine. */
object LlmChatModelHelper : LlmModelHelper {
  // Per-model cleanup callback registered on first inference.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  @OptIn(ExperimentalApi::class)
  override fun initialize(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val accelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val visionAccelerator =
      model.getStringConfigValue(
        key = ConfigKeys.VISION_ACCELERATOR,
        defaultValue = DEFAULT_VISION_ACCELERATOR.label,
      )

    val textBackend = backendFor(context, accelerator, default = Backend.CPU())
    val visionBackend = backendFor(context, visionAccelerator, default = Backend.GPU())
    Log.i(
      TAG,
      "Backend resolve for '${model.name}': accelerator='$accelerator' → text=$textBackend, " +
        "vision='$visionAccelerator'(enabled=$supportImage), audioEnabled=$supportAudio",
    )

    val modelPath = model.getPath(context = context)
    val engineConfig =
      EngineConfig(
        modelPath = modelPath,
        backend = textBackend,
        visionBackend = if (supportImage) visionBackend else null, // must be GPU for Gemma 3n
        audioBackend = if (supportAudio) Backend.CPU() else null, // must be CPU for Gemma 3n
        maxNumTokens = maxTokens,
        cacheDir =
          if (modelPath.startsWith("/data/local/tmp")) {
            context.getExternalFilesDir(null)?.absolutePath
          } else {
            null
          },
      )

    // Speculative decoding requires both a model that supports it and the user toggle.
    val modelSupportsSpeculative =
      try {
        Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
      } catch (e: Exception) {
        false
      }

    try {
      val speculativeDecoding =
        modelSupportsSpeculative &&
          model.capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING]?.contains(taskId) ==
            true &&
          model.getBooleanConfigValue(
            key = ConfigKeys.ENABLE_SPECULATIVE_DECODING,
            defaultValue = false,
          )

      ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
      Log.d(TAG, "Speculative decoding enabled: $speculativeDecoding")
      val engine = Engine(engineConfig)
      engine.initialize()
      ExperimentalFlags.enableSpeculativeDecoding = false

      val conversation =
        withConstrainedDecoding(enableConversationConstrainedDecoding) {
          engine.createConversation(
            ConversationConfig(
              samplerConfig = samplerFor(model, textBackend is Backend.NPU),
              systemInstruction = systemInstruction,
              tools = tools,
            )
          )
        }
      model.instance = LlmModelInstance(engine = engine, conversation = conversation)
    } catch (e: Exception) {
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
    onDone("")
  }

  @OptIn(ExperimentalApi::class)
  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {
    try {
      Log.d(TAG, "Resetting conversation for model '${model.name}'")
      val instance = model.instance as? LlmModelInstance ?: return
      instance.conversation.close()

      Log.d(TAG, "Enable image: $supportImage, enable audio: $supportAudio")
      val accelerator =
        model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)

      instance.conversation =
        withConstrainedDecoding(enableConversationConstrainedDecoding) {
          instance.engine.createConversation(
            ConversationConfig(
              samplerConfig = samplerFor(model, isNpuAccelerator(accelerator)),
              systemInstruction = systemInstruction,
              tools = tools,
              initialMessages = initialMessages,
            )
          )
        }
      Log.d(TAG, "Resetting done")
    } catch (e: Exception) {
      Log.d(TAG, "Failed to reset conversation", e)
    }
  }

  /**
   * Spins up an additional conversation on the already-loaded engine, independent of the instance's
   * primary one. The assistant popup uses this for an ephemeral chat that won't perturb the main
   * on-screen conversation. Returns null when no model is loaded.
   */
  @OptIn(ExperimentalApi::class)
  fun createDetachedConversation(
    model: Model,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
  ): Conversation? {
    val instance = model.instance as? LlmModelInstance ?: return null
    val accelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    return instance.engine.createConversation(
      ConversationConfig(
        samplerConfig = samplerFor(model, isNpuAccelerator(accelerator)),
        systemInstruction = systemInstruction,
        tools = tools,
      )
    )
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    val instance = model.instance as? LlmModelInstance ?: return

    try {
      instance.conversation.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the conversation: ${e.message}")
    }
    try {
      instance.engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the engine: ${e.message}")
    }

    cleanUpListeners.remove(model.name)?.invoke()
    model.instance = null

    onDone()
    Log.d(TAG, "Clean up done.")
  }

  override fun stopResponse(model: Model) {
    (model.instance as? LlmModelInstance)?.conversation?.cancelProcess()
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      onError("LlmModelInstance is not initialized.")
      return
    }

    cleanUpListeners.putIfAbsent(model.name, cleanUpListener)

    val contents = buildList {
      images.forEach { add(Content.ImageBytes(it.toPngByteArray())) }
      audioClips.forEach { add(Content.AudioBytes(it)) }
      // Text must be last so the final token reflects the prompt accurately.
      if (input.trim().isNotEmpty()) {
        add(Content.Text(input))
      }
    }

    instance.conversation.sendMessageAsync(
      Contents.of(contents),
      object : MessageCallback {
        override fun onMessage(message: Message) {
          resultListener(message.toString(), false, message.channels["thought"])
        }

        override fun onDone() {
          resultListener("", true, null)
        }

        override fun onError(throwable: Throwable) {
          if (throwable is CancellationException) {
            Log.i(TAG, "The inference is cancelled.")
            resultListener("", true, null)
          } else {
            Log.e(TAG, "onError", throwable)
            onError("Error: ${throwable.message}")
          }
        }
      },
      extraContext ?: emptyMap(),
    )
  }

  /** Resolves an [Accelerator] label to its LiteRT-LM [Backend], falling back to [default]. */
  private fun backendFor(context: Context, label: String, default: Backend): Backend =
    when (label) {
      Accelerator.CPU.label -> Backend.CPU()
      Accelerator.GPU.label -> Backend.GPU()
      Accelerator.NPU.label,
      Accelerator.TPU.label -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
      else -> default
    }

  private fun isNpuAccelerator(label: String): Boolean =
    label == Accelerator.NPU.label || label == Accelerator.TPU.label

  /** NPU backends use the engine's built-in sampler (null); everything else uses model config. */
  private fun samplerFor(model: Model, isNpu: Boolean): SamplerConfig? {
    if (isNpu) {
      return null
    }
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    return SamplerConfig(topK = topK, topP = topP.toDouble(), temperature = temperature.toDouble())
  }

  /** Runs [block] with the constrained-decoding flag set, then restores it to false. */
  @OptIn(ExperimentalApi::class)
  private inline fun <T> withConstrainedDecoding(enabled: Boolean, block: () -> T): T {
    ExperimentalFlags.enableConversationConstrainedDecoding = enabled
    try {
      return block()
    } finally {
      ExperimentalFlags.enableConversationConstrainedDecoding = false
    }
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }
}
