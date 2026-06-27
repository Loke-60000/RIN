// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

/** A configuration value that may be backed by an int, float, or string. */
sealed class ConfigValue {
  data class IntValue(val value: Int) : ConfigValue()

  data class FloatValue(val value: Float) : ConfigValue()

  data class StringValue(val value: String) : ConfigValue()
}

/** Reads [configValue] as an int, coercing floats and falling back to [default] when null. */
fun getIntConfigValue(configValue: ConfigValue?, default: Int): Int =
  when (configValue) {
    null -> default
    is ConfigValue.IntValue -> configValue.value
    is ConfigValue.FloatValue -> configValue.value.toInt()
    is ConfigValue.StringValue -> 0
  }

/** Reads [configValue] as a float, coercing ints and falling back to [default] when null. */
fun getFloatConfigValue(configValue: ConfigValue?, default: Float): Float =
  when (configValue) {
    null -> default
    is ConfigValue.IntValue -> configValue.value.toFloat()
    is ConfigValue.FloatValue -> configValue.value
    is ConfigValue.StringValue -> 0f
  }

/** Reads [configValue] as a string, formatting numbers and falling back to [default] when null. */
fun getStringConfigValue(configValue: ConfigValue?, default: String): String =
  when (configValue) {
    null -> default
    is ConfigValue.IntValue -> configValue.value.toString()
    is ConfigValue.FloatValue -> configValue.value.toString()
    is ConfigValue.StringValue -> configValue.value
  }
