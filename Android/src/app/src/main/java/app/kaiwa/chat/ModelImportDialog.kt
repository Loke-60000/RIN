// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kaiwa.R
import app.kaiwa.common.isPixel10
import app.kaiwa.data.Accelerator
import app.kaiwa.data.BooleanSwitchConfig
import app.kaiwa.data.Config
import app.kaiwa.data.ConfigKey
import app.kaiwa.data.ConfigKeys
import app.kaiwa.data.DEFAULT_MAX_TOKEN
import app.kaiwa.data.DEFAULT_TEMPERATURE
import app.kaiwa.data.DEFAULT_TOPK
import app.kaiwa.data.DEFAULT_TOPP
import app.kaiwa.data.IMPORTS_DIR
import app.kaiwa.data.LabelConfig
import app.kaiwa.data.NumberSliderConfig
import app.kaiwa.data.SegmentedButtonConfig
import app.kaiwa.data.ValueType
import app.kaiwa.data.convertValueToTargetType
import app.kaiwa.proto.ImportedModel
import app.kaiwa.proto.LlmConfig
import app.kaiwa.ui.common.ConfigEditorsPanel
import app.kaiwa.ui.common.ensureValidFileName
import app.kaiwa.ui.common.humanReadableSize
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGModelImportDialog"

// The NPU path can't run alongside GPU on a Pixel 10, so GPU is dropped there.
private val SUPPORTED_ACCELERATORS: List<Accelerator> =
  if (isPixel10()) {
    listOf(Accelerator.CPU, Accelerator.NPU)
  } else {
    listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU)
  }

private val IMPORT_CONFIGS_LLM: List<Config> =
  listOf(
    LabelConfig(key = ConfigKeys.NAME),
    LabelConfig(key = ConfigKeys.MODEL_TYPE),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_MAX_TOKENS,
      sliderMin = 1024f,
      sliderMax = 32768f,
      defaultValue = DEFAULT_MAX_TOKEN.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPK,
      sliderMin = 1f,
      sliderMax = 100f,
      defaultValue = DEFAULT_TOPK.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPP,
      sliderMin = 0.0f,
      sliderMax = 1.0f,
      defaultValue = DEFAULT_TOPP,
      valueType = ValueType.FLOAT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TEMPERATURE,
      sliderMin = 0.0f,
      sliderMax = 2.0f,
      defaultValue = DEFAULT_TEMPERATURE,
      valueType = ValueType.FLOAT,
    ),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_IMAGE, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_AUDIO, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_MOBILE_ACTIONS, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_THINKING, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_SPECULATIVE_DECODING, defaultValue = false),
    SegmentedButtonConfig(
      key = ConfigKeys.COMPATIBLE_ACCELERATORS,
      defaultValue = SUPPORTED_ACCELERATORS[0].label,
      options = SUPPORTED_ACCELERATORS.map { it.label },
      allowMultiple = true,
    ),
  )

@Composable
fun ModelImportDialog(
  uri: Uri,
  onDismiss: () -> Unit,
  onDone: (ImportedModel) -> Unit,
  defaultValues: Map<ConfigKey, Any> = emptyMap(),
) {
  val context = LocalContext.current
  val info = remember { getFileSizeAndDisplayNameFromUri(context = context, uri = uri) }
  var fileSize by remember { mutableLongStateOf(info.first) }
  val fileName by remember { mutableStateOf(ensureValidFileName(info.second)) }

  LaunchedEffect(uri) {
    if (uri.isRemote()) {
      kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
          // Probe the resolved download URL for a Content-Length to show before importing.
          val connection = java.net.URL(getDownloadUrl(uri)).openConnection()
          connection.connect()
          val size = connection.contentLengthLong
          if (size > 0) {
            fileSize = size
          }
          connection.getInputStream().close()
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }
  }

  val initialValues: Map<String, Any> = remember {
    mutableMapOf<String, Any>().apply {
      for (config in IMPORT_CONFIGS_LLM) {
        put(config.key.label, config.defaultValue)
      }
      put(ConfigKeys.NAME.label, fileName)
      // TODO: support other types.
      put(ConfigKeys.MODEL_TYPE.label, "LLM")

      for ((key, value) in defaultValues) {
        put(key.label, value)
      }
    }
  }
  val values: SnapshotStateMap<String, Any> = remember {
    mutableStateMapOf<String, Any>().apply { putAll(initialValues) }
  }
  val interactionSource = remember { MutableInteractionSource() }

  Dialog(onDismissRequest = onDismiss) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth().clickable(
          interactionSource = interactionSource,
          indication = null,
        ) {
          focusManager.clearFocus()
        },
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          "Import Model",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp),
        )

        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          ConfigEditorsPanel(configs = IMPORT_CONFIGS_LLM, values = values)
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = { onDismiss() }) { Text("Cancel") }

          Button(
            onClick = {
              val importedModel = buildImportedModel(values, uri, fileName, fileSize)
              onDone(importedModel)
            }
          ) {
            Text("Import")
          }
        }
      }
    }
  }
}

private fun buildImportedModel(
  values: SnapshotStateMap<String, Any>,
  uri: Uri,
  fileName: String,
  fileSize: Long,
): ImportedModel {
  fun <T> read(key: ConfigKey, type: ValueType): T {
    @Suppress("UNCHECKED_CAST")
    return convertValueToTargetType(value = values.getValue(key.label), valueType = type) as T
  }

  val accelerators =
    read<String>(ConfigKeys.COMPATIBLE_ACCELERATORS, ValueType.STRING).split(",")
  val downloadUrl = getDownloadUrl(uri)

  val llmConfig =
    LlmConfig.newBuilder()
      .addAllCompatibleAccelerators(accelerators)
      .setDefaultMaxTokens(read(ConfigKeys.DEFAULT_MAX_TOKENS, ValueType.INT))
      .setDefaultTopk(read(ConfigKeys.DEFAULT_TOPK, ValueType.INT))
      .setDefaultTopp(read(ConfigKeys.DEFAULT_TOPP, ValueType.FLOAT))
      .setDefaultTemperature(read(ConfigKeys.DEFAULT_TEMPERATURE, ValueType.FLOAT))
      .setSupportImage(read(ConfigKeys.SUPPORT_IMAGE, ValueType.BOOLEAN))
      .setSupportAudio(read(ConfigKeys.SUPPORT_AUDIO, ValueType.BOOLEAN))
      .setSupportMobileActions(read(ConfigKeys.SUPPORT_MOBILE_ACTIONS, ValueType.BOOLEAN))
      .setSupportThinking(read(ConfigKeys.SUPPORT_THINKING, ValueType.BOOLEAN))
      .setSupportSpeculativeDecoding(
        read(ConfigKeys.SUPPORT_SPECULATIVE_DECODING, ValueType.BOOLEAN)
      )
      .build()

  return ImportedModel.newBuilder()
    .setFileName(fileName)
    .setFileSize(fileSize)
    .setUrl(if (uri.isRemote()) downloadUrl else "")
    .setLlmConfig(llmConfig)
    .build()
}

@Composable
fun ModelImportingDialog(
  uri: Uri,
  info: ImportedModel,
  onDismiss: () -> Unit,
  onDone: (ImportedModel) -> Unit,
) {
  var error by remember { mutableStateOf("") }
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var progress by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(Unit) {
    importModel(
      context = context,
      coroutineScope = coroutineScope,
      fileName = info.fileName,
      fileSize = info.fileSize,
      uri = uri,
      onDone = { onDone(info) },
      onProgress = { progress = it },
      onError = { error = it },
    )
  }

  Dialog(
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    onDismissRequest = onDismiss,
  ) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          "Import Model",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp),
        )

        if (error.isEmpty()) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              "${info.fileName} (${info.fileSize.humanReadableSize()})",
              style = MaterialTheme.typography.labelSmall,
            )
            val animatedProgress = remember { Animatable(0f) }
            LinearProgressIndicator(
              progress = { animatedProgress.value },
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            LaunchedEffect(progress) {
              animatedProgress.animateTo(progress, animationSpec = tween(150))
            }
          }
        } else {
          Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              Icons.Rounded.Error,
              contentDescription = stringResource(R.string.cd_error),
              tint = MaterialTheme.colorScheme.error,
            )
            Text(
              error,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { onDismiss() }) { Text("Close") }
          }
        }
      }
    }
  }
}

private fun importModel(
  context: Context,
  coroutineScope: CoroutineScope,
  fileName: String,
  fileSize: Long,
  uri: Uri,
  onDone: () -> Unit,
  onProgress: (Float) -> Unit,
  onError: (String) -> Unit,
) {
  // TODO: handle error.
  coroutineScope.launch(Dispatchers.IO) {
    // Web models stay at their URL; nothing to copy locally.
    if (uri.isRemote()) {
      Log.d(TAG, "importing web model from $uri. File name: $fileName. File size: $fileSize")
      Log.d(TAG, "import done for web model")
      onDone()
      return@launch
    }

    val decodedUri = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8.name())
    Log.d(TAG, "importing model from $decodedUri. File name: $fileName. File size: $fileSize")

    val importsDir = File(context.getExternalFilesDir(null), IMPORTS_DIR)
    if (!importsDir.exists()) {
      importsDir.mkdirs()
    }

    val outputFile = File(context.getExternalFilesDir(null), "$IMPORTS_DIR/$fileName")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytesRead: Int
    var lastSetProgressTs: Long = 0
    var importedBytes = 0L

    val inputStream = context.contentResolver.openInputStream(uri)
    val outputStream = FileOutputStream(outputFile)
    try {
      if (inputStream != null) {
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
          outputStream.write(buffer, 0, bytesRead)
          importedBytes += bytesRead

          // Throttle progress callbacks to roughly every 200 ms.
          val curTs = System.currentTimeMillis()
          if (curTs - lastSetProgressTs > 200) {
            Log.d(TAG, "importing progress: $importedBytes, $fileSize")
            lastSetProgressTs = curTs
            if (fileSize != 0L) {
              onProgress(importedBytes.toFloat() / fileSize.toFloat())
            }
          }
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
      onError(e.message ?: "Failed to import")
      return@launch
    } finally {
      inputStream?.close()
      outputStream.close()
    }
    Log.d(TAG, "import done")
    onProgress(1f)
    onDone()
  }
}

private fun getFileSizeAndDisplayNameFromUri(context: Context, uri: Uri): Pair<Long, String> {
  if (uri.isRemote()) {
    return Pair(0L, uri.lastPathSegment ?: "")
  }

  var fileSize = 0L
  var displayName = ""
  try {
    context.contentResolver
      .query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
          displayName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
      }
  } catch (e: Exception) {
    e.printStackTrace()
    return Pair(0L, "")
  }
  return Pair(fileSize, displayName)
}

// Rewrites a Hugging Face blob URL into its direct resolve URL; other URLs pass through unchanged.
private fun getDownloadUrl(uri: Uri): String {
  val raw = uri.toString()
  return if (raw.contains("huggingface.co") && raw.contains("/blob/")) {
    raw.replaceFirst("/blob/", "/resolve/")
  } else {
    raw
  }
}

private fun Uri.isRemote(): Boolean = scheme == "http" || scheme == "https"
