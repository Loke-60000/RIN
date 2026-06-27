// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

import androidx.annotation.StringRes
import kotlin.math.abs

/** UI widget used to edit a configuration value. */
enum class ConfigEditorType {
  LABEL,
  NUMBER_SLIDER,
  BOOLEAN_SWITCH,
  SEGMENTED_BUTTON,
  BOTTOMSHEET_SELECTOR,
}

/** Runtime type of a configuration value. */
enum class ValueType {
  INT,
  FLOAT,
  DOUBLE,
  STRING,
  BOOLEAN,
}

data class ConfigKey(val id: String, val label: String)

/** Registry of every configuration key used across the app. */
object ConfigKeys {
  val MAX_TOKENS = ConfigKey("max_tokens", "Max tokens")
  val MAX_OUTPUT_TOKENS = ConfigKey("max_output_tokens", "Max output tokens")
  val TOPK = ConfigKey("topk", "TopK")
  val TOPP = ConfigKey("topp", "TopP")
  val TEMPERATURE = ConfigKey("temperature", "Temperature")
  val DEFAULT_MAX_TOKENS = ConfigKey("default_max_tokens", "Default max tokens")
  val DEFAULT_TOPK = ConfigKey("default_topk", "Default TopK")
  val DEFAULT_TOPP = ConfigKey("default_topp", "Default TopP")
  val DEFAULT_TEMPERATURE = ConfigKey("default_temperature", "Default temperature")
  val SUPPORT_IMAGE = ConfigKey("support_image", "Support image")
  val SUPPORT_AUDIO = ConfigKey("support_audio", "Support audio")
  val SUPPORT_TINY_GARDEN = ConfigKey("support_tiny_garden", "Support tiny garden")
  val SUPPORT_MOBILE_ACTIONS = ConfigKey("support_mobile_actions", "Support mobile actions")
  val SUPPORT_THINKING = ConfigKey("support_thinking", "Support thinking")
  val SUPPORT_SPECULATIVE_DECODING =
    ConfigKey("support_speculative_decoding", "Support speculative decoding")
  val ENABLE_THINKING = ConfigKey("enable_thinking", "Enable thinking")
  val ENABLE_SPECULATIVE_DECODING =
    ConfigKey("enable_speculative_decoding", "Enable speculative decoding")
  val MAX_RESULT_COUNT = ConfigKey("max_result_count", "Max result count")
  val USE_GPU = ConfigKey("use_gpu", "Use GPU")
  val ACCELERATOR = ConfigKey("accelerator", "Accelerator")
  val VISION_ACCELERATOR = ConfigKey("vision_accelerator", "Vision accelerator")
  val COMPATIBLE_ACCELERATORS = ConfigKey("compatible_accelerators", "Compatible accelerators")
  val WARM_UP_ITERATIONS = ConfigKey("warm_up_iterations", "Warm up iterations")
  val BENCHMARK_ITERATIONS = ConfigKey("benchmark_iterations", "Benchmark iterations")
  val ITERATIONS = ConfigKey("iterations", "Iterations")
  val THEME = ConfigKey("theme", "Theme")
  val NAME = ConfigKey("name", "Name")
  val MODEL_TYPE = ConfigKey("model_type", "Model type")
  val MODEL = ConfigKey("model", "Model")
  val RESET_CONVERSATION_TURN_COUNT =
    ConfigKey("reset_conversation_turn_count", "Number of turns before the conversation resets")
  val PREFILL_TOKENS = ConfigKey("prefill_tokens", "Prefill tokens")
  val DECODE_TOKENS = ConfigKey("decode_tokens", "Decode tokens")
  val NUMBER_OF_RUNS = ConfigKey("number_of_runs", "Number of runs")
}

/**
 * One configurable model setting.
 *
 * @param type which editor widget renders this setting.
 * @param key unique identity of the setting.
 * @param defaultValue value used until the user changes it.
 * @param valueType runtime type of the stored value.
 * @param needReinitialization when true, editing this setting re-initializes the model.
 */
open class Config(
  val type: ConfigEditorType,
  open val key: ConfigKey,
  open val defaultValue: Any,
  open val valueType: ValueType,
  open val needReinitialization: Boolean = true,
)

/** A read-only text setting. */
class LabelConfig(override val key: ConfigKey, override val defaultValue: String = "") :
  Config(
    type = ConfigEditorType.LABEL,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.STRING,
  )

/** A numeric setting edited via a slider bounded by [sliderMin]..[sliderMax]. */
class NumberSliderConfig(
  override val key: ConfigKey,
  val sliderMin: Float,
  val sliderMax: Float,
  override val defaultValue: Float,
  override val valueType: ValueType,
  override val needReinitialization: Boolean = true,
) :
  Config(
    type = ConfigEditorType.NUMBER_SLIDER,
    key = key,
    defaultValue = defaultValue,
    valueType = valueType,
  )

/** An on/off setting. */
class BooleanSwitchConfig(
  override val key: ConfigKey,
  override val defaultValue: Boolean,
  override val needReinitialization: Boolean = true,
) :
  Config(
    type = ConfigEditorType.BOOLEAN_SWITCH,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.BOOLEAN,
  )

/**
 * A choice between [options]. When [allowMultiple] is true the stored value is the selected labels
 * joined by commas.
 */
class SegmentedButtonConfig(
  override val key: ConfigKey,
  override val defaultValue: String,
  val options: List<String>,
  val allowMultiple: Boolean = false,
) :
  Config(
    type = ConfigEditorType.SEGMENTED_BUTTON,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.STRING,
  )

/** A choice rendered in a bottom-sheet picker. */
class BottomSheetSelectorConfig(
  override val key: ConfigKey,
  override val defaultValue: String,
  val options: List<BottomSheetSelectorItem>,
  @StringRes val bottomSheetTitleResId: Int? = null,
) :
  Config(
    type = ConfigEditorType.BOTTOMSHEET_SELECTOR,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.STRING,
  )

data class BottomSheetSelectorItem(val label: String)

/** Coerces [value] into the representation demanded by [valueType]. */
fun convertValueToTargetType(value: Any, valueType: ValueType): Any =
  when (valueType) {
    ValueType.INT ->
      when (value) {
        is Int -> value
        is Float -> value.toInt()
        is Double -> value.toInt()
        is String -> value.toIntOrNull() ?: ""
        is Boolean -> if (value) 1 else 0
        else -> ""
      }
    ValueType.FLOAT ->
      when (value) {
        is Int -> value.toFloat()
        is Float -> value
        is Double -> value.toFloat()
        is String -> value.toFloatOrNull() ?: ""
        is Boolean -> if (value) 1f else 0f
        else -> ""
      }
    ValueType.DOUBLE ->
      when (value) {
        is Int -> value.toDouble()
        is Float -> value.toDouble()
        is Double -> value
        is String -> value.toDoubleOrNull() ?: ""
        is Boolean -> if (value) 1.0 else 0.0
        else -> ""
      }
    ValueType.BOOLEAN ->
      when (value) {
        is Int -> value == 0
        is Boolean -> value
        is Float -> abs(value) > 1e-6
        is Double -> abs(value) > 1e-6
        is String -> value.isNotEmpty()
        else -> false
      }
    ValueType.STRING -> value.toString()
  }

/** Builds the standard LiteRT-LM chat settings, optionally with a slider-bounded token cap. */
fun createLlmChatConfigs(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  defaultMaxContextLength: Int? = null,
  defaultTopK: Int = DEFAULT_TOPK,
  defaultTopP: Float = DEFAULT_TOPP,
  defaultTemperature: Float = DEFAULT_TEMPERATURE,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
  supportThinking: Boolean = false,
  supportSpeculativeDecoding: Boolean = false,
): List<Config> {
  // Context budget is always editable. When the model advertises a context length we cap the
  // slider there; otherwise we allow up to the 8k default (or higher if the model asks for more).
  val maxTokensConfig: Config =
    if (defaultMaxContextLength != null) {
      NumberSliderConfig(
        key = ConfigKeys.MAX_TOKENS,
        sliderMin = 1024f,
        sliderMax = defaultMaxContextLength.toFloat(),
        defaultValue = defaultMaxToken.toFloat().coerceIn(1024f, defaultMaxContextLength.toFloat()),
        valueType = ValueType.INT,
      )
    } else {
      NumberSliderConfig(
        key = ConfigKeys.MAX_TOKENS,
        sliderMin = 1024f,
        sliderMax = maxOf(8192f, defaultMaxToken.toFloat()),
        defaultValue = defaultMaxToken.toFloat(),
        valueType = ValueType.INT,
      )
    }

  val configs =
    mutableListOf(
      maxTokensConfig,
      NumberSliderConfig(
        key = ConfigKeys.TOPK,
        sliderMin = 1f,
        sliderMax = 100f,
        defaultValue = defaultTopK.toFloat(),
        valueType = ValueType.INT,
      ),
      NumberSliderConfig(
        key = ConfigKeys.TOPP,
        sliderMin = 0.0f,
        sliderMax = 1.0f,
        defaultValue = defaultTopP,
        valueType = ValueType.FLOAT,
      ),
      NumberSliderConfig(
        key = ConfigKeys.TEMPERATURE,
        sliderMin = 0.0f,
        sliderMax = 2.0f,
        defaultValue = defaultTemperature,
        valueType = ValueType.FLOAT,
      ),
      SegmentedButtonConfig(
        key = ConfigKeys.ACCELERATOR,
        defaultValue = accelerators[0].label,
        options = accelerators.map { it.label },
      ),
    )

  if (supportThinking) {
    configs.add(BooleanSwitchConfig(key = ConfigKeys.ENABLE_THINKING, defaultValue = false))
  }
  if (supportSpeculativeDecoding) {
    configs.add(
      BooleanSwitchConfig(key = ConfigKeys.ENABLE_SPECULATIVE_DECODING, defaultValue = false)
    )
  }
  return configs
}

/**
 * Settings for an NPU-only LLM. These models currently can't tune topK/topP/temperature, so only a
 * token cap and accelerator selector are exposed.
 */
fun createLlmChatConfigsForNpuModel(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
): List<Config> =
  listOf(
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken"),
    SegmentedButtonConfig(
      key = ConfigKeys.ACCELERATOR,
      defaultValue = accelerators[0].label,
      options = accelerators.map { it.label },
    ),
  )

/**
 * Settings for an AICore model. AICore exposes topK and temperature (the latter clamped to
 * 0.0..1.0) but not topP.
 */
fun createAICoreConfigs(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  defaultTopK: Int = DEFAULT_TOPK,
  defaultTemperature: Float = DEFAULT_TEMPERATURE,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
  defaultMaxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKEN,
): List<Config> =
  listOf(
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken"),
    NumberSliderConfig(
      key = ConfigKeys.MAX_OUTPUT_TOKENS,
      sliderMin = 100f,
      sliderMax = 4096f,
      defaultValue = defaultMaxOutputTokens.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.TOPK,
      sliderMin = 1f,
      sliderMax = 100f,
      defaultValue = defaultTopK.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.TEMPERATURE,
      sliderMin = 0.0f,
      sliderMax = 1.0f,
      defaultValue = defaultTemperature,
      valueType = ValueType.FLOAT,
    ),
    SegmentedButtonConfig(
      key = ConfigKeys.ACCELERATOR,
      defaultValue = accelerators[0].label,
      options = accelerators.map { it.label },
    ),
  )

/** Formats [value] for display, using two decimals for floats. */
fun getConfigValueString(value: Any, config: Config): String =
  if (config.valueType == ValueType.FLOAT) "%.2f".format(value) else "$value"
