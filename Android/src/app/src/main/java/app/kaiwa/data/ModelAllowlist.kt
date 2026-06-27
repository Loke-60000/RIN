// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

import android.os.Build
import android.util.Log
import app.kaiwa.common.isPixel10
import app.kaiwa.common.isPixelDevice
import com.google.gson.annotations.SerializedName

private const val TAG = "AGModelAllowlist"

/** Per-model default inference settings as published in the allowlist JSON. */
data class DefaultConfig(
  @SerializedName("topK") val topK: Int?,
  @SerializedName("topP") val topP: Float?,
  @SerializedName("temperature") val temperature: Float?,
  @SerializedName("accelerators") val accelerators: String?,
  @SerializedName("visionAccelerator") val visionAccelerator: String?,
  @SerializedName("maxContextLength") val maxContextLength: Int?,
  @SerializedName("maxTokens") val maxTokens: Int?,
)

/** A model file published for a specific SoC. */
data class SocModelFile(
  @SerializedName("modelFile") val modelFile: String?,
  @SerializedName("url") val url: String?,
  @SerializedName("commitHash") val commitHash: String?,
  @SerializedName("sizeInBytes") val sizeInBytes: Long?,
)

/** A single entry in the model allowlist JSON. */
data class AllowedModel(
  val name: String,
  val modelId: String,
  val modelFile: String,
  val commitHash: String,
  val description: String,
  val sizeInBytes: Long,
  val defaultConfig: DefaultConfig,
  val taskTypes: List<String>,
  val disabled: Boolean? = null,
  val llmSupportImage: Boolean? = null,
  val llmSupportAudio: Boolean? = null,
  val llmSupportMobileActions: Boolean? = null,
  val capabilities: List<ModelCapability>? = null,
  val minDeviceMemoryInGb: Int? = null,
  val bestForTaskTypes: List<String>? = null,
  val localModelFilePathOverride: String? = null,
  val url: String? = null,
  val socToModelFiles: Map<String, SocModelFile>? = null,
  val runtimeType: RuntimeType? = null,
  val aicoreReleaseStage: AICoreModelReleaseStage? = null,
  val aicorePreference: AICoreModelPreference? = null,
  val parentModelName: String? = null,
  val variantLabel: String? = null,
  val capabilityToTaskTypes: Map<ModelCapability, List<String>>? = null,
  val updatableModelFiles: List<ModelFile>? = null,
  val updateInfo: String? = null,
) {
  /** Materializes a runtime [Model] from this allowlist entry. */
  fun toModel(): Model {
    // Default to the generic HF download triplet; a SoC-specific entry may override it below.
    var version = commitHash
    var downloadedFileName = modelFile
    var downloadUrl = url ?: hfResolveUrl(modelId, commitHash, modelFile)
    var sizeInBytes = sizeInBytes

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && socToModelFiles?.isNotEmpty() == true) {
      socToModelFiles[SOC]?.let { soc ->
        Log.d(TAG, "Found soc-specific model files for model $name: $soc")
        version = soc.commitHash ?: "-"
        downloadedFileName = soc.modelFile ?: "-"
        downloadUrl = soc.url ?: hfResolveUrl(modelId, soc.commitHash, soc.modelFile)
        sizeInBytes = soc.sizeInBytes ?: -1
      }
    }

    val isLlmModel =
      taskTypes.any {
        it == BuiltInTaskId.LLM_CHAT ||
          it == BuiltInTaskId.LLM_PROMPT_LAB ||
          it == BuiltInTaskId.LLM_ASK_AUDIO ||
          it == BuiltInTaskId.LLM_ASK_IMAGE ||
          it == BuiltInTaskId.LLM_MOBILE_ACTIONS
      }

    var configs: List<Config> = listOf()
    var llmMaxToken = DEFAULT_MAX_TOKEN
    var accelerators: List<Accelerator> = DEFAULT_ACCELERATORS
    var visionAccelerator: Accelerator = DEFAULT_VISION_ACCELERATOR

    // On Pixel devices the published "NPU" branding is presented as "TPU".
    var finalDescription = description
    var acceleratorsStr = defaultConfig.accelerators
    if (isPixelDevice()) {
      finalDescription = description.replace(Regex("\\bNPU\\b"), "TPU")
      acceleratorsStr = acceleratorsStr?.replace(Regex("\\bnpu\\b"), "tpu")
    }

    if (isLlmModel) {
      val defaultTopK = defaultConfig.topK ?: DEFAULT_TOPK
      val defaultTopP = defaultConfig.topP ?: DEFAULT_TOPP
      val defaultTemperature = defaultConfig.temperature ?: DEFAULT_TEMPERATURE
      llmMaxToken = defaultConfig.maxTokens ?: DEFAULT_MAX_TOKEN
      val llmMaxContextLength = defaultConfig.maxContextLength

      if (acceleratorsStr != null) {
        accelerators =
          acceleratorsStr.split(",").mapNotNull { token ->
            when (token) {
              "cpu" -> Accelerator.CPU
              "gpu" -> Accelerator.GPU
              "npu" -> Accelerator.NPU
              "tpu" -> Accelerator.TPU
              else -> null
            }
          }
        // Pixel 10 can't run these models on the GPU.
        if (isPixel10()) {
          accelerators = accelerators.filterNot { it == Accelerator.GPU }
        }
      }

      visionAccelerator =
        when (defaultConfig.visionAccelerator) {
          "cpu" -> Accelerator.CPU
          "gpu" -> Accelerator.GPU
          "npu" -> Accelerator.NPU
          else -> visionAccelerator
        }

      val npuOnly =
        accelerators.size == 1 &&
          (accelerators[0] == Accelerator.NPU || accelerators[0] == Accelerator.TPU)

      configs =
        when {
          runtimeType == RuntimeType.AICORE ->
            createAICoreConfigs(
              defaultTopK = defaultTopK,
              defaultTemperature = defaultTemperature.coerceAtMost(1.0f),
              defaultMaxToken = llmMaxToken,
              accelerators = accelerators,
            )
          npuOnly ->
            createLlmChatConfigsForNpuModel(
              defaultMaxToken = llmMaxToken,
              accelerators = accelerators,
            )
          else ->
            createLlmChatConfigs(
              defaultTopK = defaultTopK,
              defaultTopP = defaultTopP,
              defaultTemperature = defaultTemperature,
              defaultMaxToken = llmMaxToken,
              defaultMaxContextLength = llmMaxContextLength,
              accelerators = accelerators,
              supportThinking = capabilities?.contains(ModelCapability.LLM_THINKING) == true,
              supportSpeculativeDecoding =
                capabilities?.contains(ModelCapability.SPECULATIVE_DECODING) == true,
            )
        }
    }

    var learnMoreUrl = "https://huggingface.co/$modelId"
    if (runtimeType == RuntimeType.AICORE) {
      downloadUrl = ""
      learnMoreUrl = "https://developers.google.com/ml-kit/terms"
    }

    // LLM models hide the benchmark / run-again affordances.
    val showButtons = !isLlmModel

    return Model(
      name = name,
      version = version,
      info = finalDescription,
      url = downloadUrl,
      sizeInBytes = sizeInBytes,
      minDeviceMemoryInGb = minDeviceMemoryInGb,
      configs = configs,
      downloadFileName = downloadedFileName,
      showBenchmarkButton = showButtons,
      showRunAgainButton = showButtons,
      learnMoreUrl = learnMoreUrl,
      llmSupportImage = llmSupportImage == true,
      llmSupportAudio = llmSupportAudio == true,
      llmSupportMobileActions = llmSupportMobileActions == true,
      capabilities = capabilities ?: emptyList(),
      llmMaxToken = llmMaxToken,
      accelerators = accelerators,
      visionAccelerator = visionAccelerator,
      bestForTaskIds = bestForTaskTypes ?: listOf(),
      localModelFilePathOverride = localModelFilePathOverride ?: "",
      isLlm = isLlmModel,
      runtimeType = runtimeType ?: RuntimeType.LITERT_LM,
      aicoreReleaseStage = aicoreReleaseStage,
      aicorePreference = aicorePreference,
      parentModelName = parentModelName,
      variantLabel = variantLabel,
      capabilityToTaskTypes = capabilityToTaskTypes ?: emptyMap(),
      updatableModelFiles = updatableModelFiles ?: listOf(),
      updateInfo = updateInfo ?: "",
      latestModelFile = ModelFile(fileName = downloadedFileName, commitHash = version),
    )
  }

  override fun toString(): String = "$modelId/$modelFile"
}

private fun hfResolveUrl(modelId: String, commitHash: String?, modelFile: String?): String =
  "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"

/** A named bucket of device models, used to gate features. */
data class NamedDeviceGroup(
  @SerializedName("groupName") val groupName: String,
  @SerializedName("description") val description: String? = null,
  @SerializedName("deviceModels") val deviceModels: List<String>,
)

/** Hardware constraints attached to a feature. */
data class DeviceRequirements(
  @SerializedName("allowedDeviceGroups") val allowedDeviceGroups: List<NamedDeviceGroup>? = null
)

/** The parsed model allowlist document. */
data class ModelAllowlist(
  val models: List<AllowedModel>,
  @SerializedName("aicoreRequirements") val aicoreRequirements: DeviceRequirements? = null,
)
