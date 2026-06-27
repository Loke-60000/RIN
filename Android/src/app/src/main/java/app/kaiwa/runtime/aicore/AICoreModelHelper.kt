// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.runtime.aicore

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import app.kaiwa.common.cleanUpMediapipeTaskErrorMessage
import app.kaiwa.data.AICoreModelPreference
import app.kaiwa.data.AICoreModelReleaseStage
import app.kaiwa.data.ConfigKeys
import app.kaiwa.data.DEFAULT_MAX_OUTPUT_TOKEN
import app.kaiwa.data.DEFAULT_TEMPERATURE
import app.kaiwa.data.DEFAULT_TOPK
import app.kaiwa.data.Model
import app.kaiwa.runtime.CleanUpListener
import app.kaiwa.runtime.LlmModelHelper
import app.kaiwa.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolProvider
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "AICoreModelHelper"

// AICore (ML Kit GenAI) clamps temperature to this range.
private const val MAX_TEMPERATURE = 1.0f

data class AICoreChatMessage(val isUser: Boolean, val text: String)

data class AICoreModelInstance(
  val generativeModel: GenerativeModel,
  val chatHistory: MutableList<AICoreChatMessage> = mutableListOf(),
  var inferenceJob: Job? = null,
)

/**
 * [LlmModelHelper] backed by on-device AICore via ML Kit GenAI.
 *
 * The ML Kit API is single-turn, so this helper keeps its own [AICoreChatMessage] history and
 * replays it into a flattened prompt on each turn.
 */
object AICoreModelHelper : LlmModelHelper {

  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

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
    if (coroutineScope == null) {
      Log.e(TAG, "CoroutineScope is required for AICoreModelHelper")
      onDone("Initialization failed: CoroutineScope is null")
      return
    }

    val generativeModel = model.generativeModel()
    coroutineScope.launch {
      try {
        when (val status = generativeModel.checkStatus()) {
          FeatureStatus.AVAILABLE -> {
            finishLoading(model, generativeModel)
            onDone("Feature is available")
          }
          FeatureStatus.DOWNLOADABLE,
          FeatureStatus.DOWNLOADING ->
            generativeModel.download().collect { downloadStatus ->
              when (downloadStatus) {
                is DownloadStatus.DownloadStarted ->
                  onDone("Downloading (${downloadStatus.bytesToDownload} bytes)")
                is DownloadStatus.DownloadProgress ->
                  onDone("Downloading (${downloadStatus.totalBytesDownloaded} bytes)")
                is DownloadStatus.DownloadFailed ->
                  onDone("Download failed: ${downloadStatus.e.message}")
                is DownloadStatus.DownloadCompleted -> {
                  finishLoading(model, generativeModel)
                  onDone("Download completed")
                }
              }
            }
          FeatureStatus.UNAVAILABLE -> {
            logAICoreAccessDetails(context)
            onDone("Feature is unavailable on this device.")
          }
          else -> onDone("Unknown feature status: $status")
        }
      } catch (e: Exception) {
        Log.e(TAG, "Initialization failed", e)
        onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      }
    }
  }

  private suspend fun finishLoading(model: Model, generativeModel: GenerativeModel) {
    generativeModel.warmup()
    updateTokenLimit(model, generativeModel)
    model.instance = AICoreModelInstance(generativeModel)
  }

  fun downloadModel(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onProgress: (Long, Long) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
  ) {
    val generativeModel = model.generativeModel()
    coroutineScope.launch {
      try {
        when (val status = generativeModel.checkStatus()) {
          FeatureStatus.AVAILABLE -> onDone()
          FeatureStatus.DOWNLOADABLE,
          FeatureStatus.DOWNLOADING -> {
            var totalBytes = model.sizeInBytes
            generativeModel.download().collect { downloadStatus ->
              when (downloadStatus) {
                is DownloadStatus.DownloadStarted -> {
                  totalBytes = downloadStatus.bytesToDownload
                  onProgress(0L, totalBytes)
                }
                is DownloadStatus.DownloadProgress ->
                  onProgress(downloadStatus.totalBytesDownloaded, totalBytes)
                is DownloadStatus.DownloadFailed ->
                  onError(downloadStatus.e.message ?: "Unknown download error")
                is DownloadStatus.DownloadCompleted -> onDone()
              }
            }
          }
          FeatureStatus.UNAVAILABLE -> {
            logAICoreAccessDetails(context)
            onError("AICore model is unavailable on this device.")
          }
          else -> onError("Unknown feature status: $status")
        }
      } catch (e: Exception) {
        Log.e(TAG, "Download failed", e)
        onError(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      }
    }
  }

  suspend fun isModelDownloaded(model: Model): Boolean =
    try {
      model.generativeModel().checkStatus() == FeatureStatus.AVAILABLE
    } catch (e: Exception) {
      Log.e(TAG, "Failed to check AICore model status", e)
      false
    }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {
    Log.d(TAG, "Resetting conversation for model '${model.name}'")
    val instance = model.instance as? AICoreModelInstance ?: return
    instance.chatHistory.clear()
    initialMessages.mapTo(instance.chatHistory) {
      AICoreChatMessage(isUser = it.role == Role.USER, text = it.contents.toString())
    }
    Log.d(TAG, "Resetting done")
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    (model.instance as? AICoreModelInstance)?.let {
      try {
        it.generativeModel.close()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to close the engine: ${e.message}")
      }
    }

    cleanUpListeners.remove(model.name)?.invoke()
    model.instance = null

    // Cleared references are left for the GC to reclaim.
    onDone()
    Log.d(TAG, "Clean up done.")
  }

  override fun stopResponse(model: Model) {
    (model.instance as? AICoreModelInstance)?.inferenceJob?.cancel()
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
    val instance = model.instance as? AICoreModelInstance
    if (instance == null) {
      onError("AICore model instance is not initialized.")
      return
    }
    if (coroutineScope == null) {
      Log.e(TAG, "CoroutineScope is required for AICoreModelHelper inference")
      onError("Inference failed: CoroutineScope is null")
      return
    }

    cleanUpListeners.putIfAbsent(model.name, cleanUpListener)

    val temperature =
      model
        .getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
        .coerceIn(0.0f, MAX_TEMPERATURE)
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val maxOutputTokens =
      model.getIntConfigValue(
        key = ConfigKeys.MAX_OUTPUT_TOKENS,
        defaultValue = DEFAULT_MAX_OUTPUT_TOKEN,
      )

    val prompt = formatChatPrompt(instance.chatHistory, input)

    instance.inferenceJob?.cancel()
    instance.inferenceJob =
      coroutineScope.launch {
        runInferenceStream(
          instance = instance,
          prompt = prompt,
          temperature = temperature,
          topK = topK,
          maxOutputTokens = maxOutputTokens,
          images = images,
          input = input,
          resultListener = resultListener,
          onError = onError,
        )
      }
  }

  private suspend fun runInferenceStream(
    instance: AICoreModelInstance,
    prompt: String,
    temperature: Float,
    topK: Int,
    maxOutputTokens: Int,
    images: List<Bitmap>,
    input: String,
    resultListener: ResultListener,
    onError: (message: String) -> Unit,
  ) {
    try {
      val request =
        // ML Kit GenAI accepts at most one image per request.
        if (images.isNotEmpty()) {
          generateContentRequest(ImagePart(images.first()), TextPart(prompt)) {
            this.temperature = temperature
            this.topK = topK
            this.maxOutputTokens = maxOutputTokens
          }
        } else {
          generateContentRequest(TextPart(prompt)) {
            this.temperature = temperature
            this.topK = topK
            this.maxOutputTokens = maxOutputTokens
          }
        }

      var fullResponse = ""
      instance.generativeModel.generateContentStream(request).collect { response ->
        val candidate = response.candidates.firstOrNull()
        val text = candidate?.text ?: ""
        fullResponse += text

        if (candidate?.finishReason != null) {
          instance.chatHistory.add(AICoreChatMessage(isUser = true, text = input))
          instance.chatHistory.add(AICoreChatMessage(isUser = false, text = fullResponse))
          resultListener(text, true, null)
        } else {
          resultListener(text, false, null)
        }
      }
    } catch (e: CancellationException) {
      Log.i(TAG, "The inference is cancelled.")
      // Leave the listener untouched so cancellation isn't mistaken for completion.
    } catch (e: Exception) {
      Log.e(TAG, "onError", e)
      onError("Error: ${e.message}")
    }
  }

  // Adopt the token limit reported by AICore as the model's max-tokens config.
  private suspend fun updateTokenLimit(model: Model, generativeModel: GenerativeModel) {
    val tokenLimit =
      try {
        generativeModel.getTokenLimit()
      } catch (e: Exception) {
        -1
      }
    if (tokenLimit > 0) {
      model.configValues =
        model.configValues.toMutableMap().apply {
          this[ConfigKeys.MAX_TOKENS.label] = tokenLimit.toString()
        }
    }
  }

  private fun Model.generativeModel(): GenerativeModel =
    Generation.getClient(generationConfig { modelConfig = aicoreModelConfig() })

  private fun Model.aicoreModelConfig() = modelConfig {
    releaseStage =
      if (aicoreReleaseStage == AICoreModelReleaseStage.PREVIEW) ModelReleaseStage.PREVIEW
      else ModelReleaseStage.STABLE
    preference =
      if (aicorePreference == AICoreModelPreference.FULL) ModelPreference.FULL
      else ModelPreference.FAST
  }

  private fun logAICoreAccessDetails(context: Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
      Log.w(
        TAG,
        "AICore is not accessible: Android version is ${android.os.Build.VERSION.SDK_INT}. " +
          "It requires at least Android T (API 33).",
      )
      return
    }

    val allowedPackages = setOf("app.kaiwa", "app.kaiwa.internal", "app.kaiwa.dev")
    if (context.packageName !in allowedPackages) {
      Log.w(
        TAG,
        "AICore is not accessible: Package name '${context.packageName}' is not allowlisted in " +
          "AICore. Allowed package names: $allowedPackages",
      )
    }

    val installed =
      try {
        context.packageManager.getPackageInfo("com.google.android.aicore", 0)
        true
      } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
      }
    if (!installed) {
      Log.w(
        TAG,
        "AICore is not accessible: com.google.android.aicore is not installed on this device.",
      )
    }
  }

  private fun formatChatPrompt(chatHistory: List<AICoreChatMessage>, input: String): String =
    buildString {
      for (message in chatHistory) {
        append(if (message.isUser) "user" else "model").append(": ").append(message.text).append("\n")
      }
      append("user: ").append(input).append("\nmodel: ")
    }
}
