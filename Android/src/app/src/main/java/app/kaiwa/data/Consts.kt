// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

import android.os.Build
import androidx.compose.ui.unit.dp

// Keys for the data passed to and from the download Worker.
const val KEY_MODEL_URL = "KEY_MODEL_URL"
const val KEY_MODEL_NAME = "KEY_MODEL_NAME"
const val KEY_MODEL_COMMIT_HASH = "KEY_MODEL_COMMIT_HASH"
const val KEY_MODEL_DOWNLOAD_MODEL_DIR = "KEY_MODEL_DOWNLOAD_MODEL_DIR"
const val KEY_MODEL_DOWNLOAD_FILE_NAME = "KEY_MODEL_DOWNLOAD_FILE_NAME"
const val KEY_MODEL_TOTAL_BYTES = "KEY_MODEL_TOTAL_BYTES"
const val KEY_MODEL_DOWNLOAD_RECEIVED_BYTES = "KEY_MODEL_DOWNLOAD_RECEIVED_BYTES"
const val KEY_MODEL_DOWNLOAD_RATE = "KEY_MODEL_DOWNLOAD_RATE"
const val KEY_MODEL_DOWNLOAD_REMAINING_MS = "KEY_MODEL_DOWNLOAD_REMAINING_SECONDS"
const val KEY_MODEL_DOWNLOAD_ERROR_MESSAGE = "KEY_MODEL_DOWNLOAD_ERROR_MESSAGE"
const val KEY_MODEL_DOWNLOAD_ACCESS_TOKEN = "KEY_MODEL_DOWNLOAD_ACCESS_TOKEN"
const val KEY_MODEL_EXTRA_DATA_URLS = "KEY_MODEL_EXTRA_DATA_URLS"
const val KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES = "KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES"
const val KEY_MODEL_IS_ZIP = "KEY_MODEL_IS_ZIP"
const val KEY_MODEL_UNZIPPED_DIR = "KEY_MODEL_UNZIPPED_DIR"
const val KEY_MODEL_START_UNZIPPING = "KEY_MODEL_START_UNZIPPING"
const val KEY_MODEL_IS_IMPORTED = "KEY_MODEL_IS_IMPORTED"

// Default inference settings applied to LLM models. On-device models default to an 8k context;
// it stays editable per model in the model manager.
const val DEFAULT_MAX_TOKEN = 8192
const val DEFAULT_TOPK = 64
const val DEFAULT_TOPP = 0.95f
const val DEFAULT_TEMPERATURE = 1.0f
const val DEFAULT_MAX_OUTPUT_TOKEN = 1024
val DEFAULT_ACCELERATORS = listOf(Accelerator.GPU)
val DEFAULT_VISION_ACCELERATOR = Accelerator.GPU

// Per-session input limits.
const val MAX_IMAGE_COUNT = 10
const val MAX_IMAGE_COUNT_AI_CORE = 1
const val MAX_RECOMMENDED_SKILL_COUNT = 15
const val MAX_AUDIO_CLIP_COUNT = 1
const val MAX_AUDIO_CLIP_DURATION_SEC = 30

// Audio capture sample rate, in Hz.
const val SAMPLE_RATE = 16000

// Size of the small info icons shown beneath each model name.
val MODEL_INFO_ICON_SIZE = 18.dp

// Suffix used for in-progress download temp files.
const val TMP_FILE_EXT = "gallerytmp"

// Lowercased SoC model of the running device, or empty when unavailable.
val SOC: String =
  run {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL ?: "" else ""
    }
    .lowercase()

// External links surfaced for Agent Skills.
object AgentSkillsURLs {
  const val REPOSITORY = "https://github.com/google-ai-edge/gallery/tree/main/skills"
  const val DISCUSSIONS = "https://github.com/google-ai-edge/gallery/discussions/categories/skills"
}
