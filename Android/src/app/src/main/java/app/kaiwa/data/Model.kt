// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

import android.content.Context
import com.google.gson.annotations.SerializedName
import java.io.File

/** An auxiliary file a model needs in addition to its main weights. */
data class ModelDataFile(
  val name: String,
  val url: String,
  val downloadFileName: String,
  val sizeInBytes: Long,
)

/** Directory (under external files) holding user-imported models. */
const val IMPORTS_DIR = "__imports"

private val NON_ALPHANUMERIC = Regex("[^a-zA-Z0-9]")

/** A ready-made prompt offered to the user. */
data class PromptTemplate(val title: String, val description: String, val prompt: String)

enum class ModelCapability {
  @SerializedName("llm_thinking") LLM_THINKING,
  @SerializedName("speculative_decoding") SPECULATIVE_DECODING,
}

enum class RuntimeType {
  @SerializedName("unknown") UNKNOWN,
  @SerializedName("litert_lm") LITERT_LM,
  @SerializedName("aicore") AICORE,
}

enum class AICoreModelReleaseStage {
  @SerializedName("stable") STABLE,
  @SerializedName("preview") PREVIEW,
}

enum class AICoreModelPreference {
  @SerializedName("fast") FAST,
  @SerializedName("full") FULL,
}

/** Identifies a specific revision of a model file. */
data class ModelFile(
  @SerializedName("fileName") val fileName: String,
  @SerializedName("commitHash") val commitHash: String,
)

/**
 * A model usable by one or more [Task]s.
 *
 * The leading group of fields describes the model itself; the download-related block configures how
 * (and whether) its files are fetched; the trailing block is app-managed scratch state populated at
 * runtime. [name] uniquely identifies the model and must not contain "/".
 */
data class Model(
  val name: String,

  /** Display name; falls back to [name] elsewhere when blank. */
  val displayName: String = "",

  /** Markdown blurb shown in the expanded info card. */
  val info: String = "",

  /** Editable parameters; surfaces a gear icon when non-empty. */
  var configs: List<Config> = listOf(),

  /** Target of the info card's "learn more" link. */
  val learnMoreUrl: String = "",

  /** Task ids this model is the recommended pick for. */
  val bestForTaskIds: List<String> = listOf(),

  /** Minimum device RAM (GB) required; below it the user is warned. */
  val minDeviceMemoryInGb: Int? = null,

  // --- Download configuration ---

  /** Source URL for the main model file (HF gated models prompt for a token). */
  val url: String = "",

  /** Main file size in bytes, used to compute download progress. */
  val sizeInBytes: Long = 0L,

  /** On-device file name for the downloaded model. */
  var downloadFileName: String = "_",

  /** Model version; part of the on-device storage path. */
  var version: String = "_",

  /** Extra files required alongside the main model. */
  val extraDataFiles: List<ModelDataFile> = listOf(),

  /** Whether this is an LLM. */
  val isLlm: Boolean = false,

  val aicoreReleaseStage: AICoreModelReleaseStage? = null,
  val aicorePreference: AICoreModelPreference? = null,

  /** Parent model name when this entry is a variant of another card. */
  val parentModelName: String? = null,

  /** Label for this variant. */
  val variantLabel: String? = null,

  /** Earlier revisions that an update flow can upgrade from. */
  val updatableModelFiles: List<ModelFile> = listOf(),

  /** Copy shown when the user inspects an available update. */
  val updateInfo: String = "",

  // --- Runtime selection ---

  val runtimeType: RuntimeType = RuntimeType.UNKNOWN,

  /**
   * Relative dir (under external files) for manually-managed model files. Resolve individual files
   * with [getPath].
   */
  val localFileRelativeDirPathOverride: String = "",

  /** Absolute model-file path override (testing). */
  val localModelFilePathOverride: String = "",

  // --- Built-in task fields ---

  val showRunAgainButton: Boolean = true,
  val showBenchmarkButton: Boolean = true,
  val isZip: Boolean = false,
  val unzipDir: String = "",
  val llmPromptTemplates: List<PromptTemplate> = listOf(),
  val llmSupportImage: Boolean = false,
  val llmSupportAudio: Boolean = false,
  val llmSupportMobileActions: Boolean = false,
  val capabilities: List<ModelCapability> = listOf(),
  val llmMaxToken: Int = 0,
  val accelerators: List<Accelerator> = listOf(),
  val visionAccelerator: Accelerator = Accelerator.GPU,
  val imported: Boolean = false,
  val capabilityToTaskTypes: Map<ModelCapability, List<String>> = mapOf(),

  // --- App-managed runtime state ---

  var normalizedName: String = "",
  var instance: Any? = null,
  var initializing: Boolean = false,
  var cleanUpAfterInit: Boolean = false,
  var configValues: Map<String, Any> = mapOf(),
  var prevConfigValues: Map<String, Any> = mapOf(),
  var totalBytes: Long = 0L,
  var accessToken: String? = null,

  /** True when an older, updatable copy of the model is what's on the device. */
  var updatable: Boolean = false,

  /** Latest known file details from the allowlist; used to reset/update to the newest revision. */
  var latestModelFile: ModelFile? = null,
) {
  init {
    normalizedName = NON_ALPHANUMERIC.replace(name, "_")
  }

  /** Seeds [configValues] from each config's default and computes [totalBytes]. */
  fun preProcess() {
    configValues = configs.associate { it.key.label to it.defaultValue }
    totalBytes = sizeInBytes + extraDataFiles.sumOf { it.sizeInBytes }
  }

  /** Resolves the on-device path for [fileName], honoring import/override/zip rules. */
  fun getPath(context: Context, fileName: String = downloadFileName): String {
    val externalDir = context.getExternalFilesDir(null)?.absolutePath ?: ""

    if (imported) {
      return listOf(externalDir, IMPORTS_DIR, fileName).joinToString(File.separator)
    }
    if (localModelFilePathOverride.isNotEmpty()) {
      return localModelFilePathOverride
    }
    if (localFileRelativeDirPathOverride.isNotEmpty()) {
      return listOf(externalDir, localFileRelativeDirPathOverride, fileName)
        .joinToString(File.separator)
    }

    val baseDir = listOf(externalDir, normalizedName, version).joinToString(File.separator)
    return if (isZip && unzipDir.isNotEmpty()) {
      listOf(baseDir, unzipDir).joinToString(File.separator)
    } else {
      listOf(baseDir, fileName).joinToString(File.separator)
    }
  }

  fun getIntConfigValue(key: ConfigKey, defaultValue: Int = 0): Int =
    typedConfigValue(key, ValueType.INT, defaultValue) as Int

  fun getFloatConfigValue(key: ConfigKey, defaultValue: Float = 0.0f): Float =
    typedConfigValue(key, ValueType.FLOAT, defaultValue) as Float

  fun getBooleanConfigValue(key: ConfigKey, defaultValue: Boolean = false): Boolean =
    typedConfigValue(key, ValueType.BOOLEAN, defaultValue) as Boolean

  fun getStringConfigValue(key: ConfigKey, defaultValue: String = ""): String =
    typedConfigValue(key, ValueType.STRING, defaultValue) as String

  fun getExtraDataFile(name: String): ModelDataFile? = extraDataFiles.find { it.name == name }

  private fun typedConfigValue(key: ConfigKey, valueType: ValueType, defaultValue: Any): Any =
    convertValueToTargetType(
      value = configValues.getOrDefault(key.label, defaultValue),
      valueType = valueType,
    )
}

enum class ModelDownloadStatusType {
  NOT_DOWNLOADED,
  PARTIALLY_DOWNLOADED,
  IN_PROGRESS,
  UNZIPPING,
  SUCCEEDED,
  FAILED,
}

data class ModelDownloadStatus(
  val status: ModelDownloadStatusType,
  val totalBytes: Long = 0,
  val receivedBytes: Long = 0,
  val errorMessage: String = "",
  val bytesPerSecond: Long = 0,
  val remainingMs: Long = 0,
)

val EMPTY_MODEL: Model =
  Model(name = "empty", downloadFileName = "empty.tflite", url = "", sizeInBytes = 0L)
