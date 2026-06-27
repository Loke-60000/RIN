// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.kaiwa.i18n.tr

/** A drawer "agent skill" page: one-shot Transcribe or Translate using the active model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolPage(tool: String, viewModel: MainChatViewModel, onMenu: () -> Unit) {
  val toolState by viewModel.toolState.collectAsState()
  val state by viewModel.uiState.collectAsState()
  val clipboard = LocalClipboardManager.current
  val isTranscribe = tool == "transcribe"
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (isTranscribe) tr("tool.transcribe", "Transcribe audio") else tr("tool.translate", "Translate")) },
        // Hamburger opens the shared nav drawer (where this tool shows as the active destination).
        navigationIcon = { AnimatedNavIcon(isBack = false, onClick = onMenu) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    }
  ) { padding ->
    Column(
      modifier =
        Modifier.padding(padding)
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      if (isTranscribe) {
        TranscribeTool(
          running = toolState.running,
          supportsAudio = state.supportsAudio,
          onTranscribe = {
            viewModel.runTool("Transcribe this audio verbatim. Output only the transcription.", it)
          },
        )
      } else {
        TranslateTool(running = toolState.running) { text, lang ->
          viewModel.runTool(
            "Translate the following text into $lang. Output only the translation, with no notes:\n\n$text"
          )
        }
      }

      if (toolState.running) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          Text(
            if (isTranscribe) tr("tool.transcribing", "Transcribing…") else tr("tool.translating", "Translating…"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (toolState.error.isNotEmpty()) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.errorContainer,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            toolState.error,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
      if (toolState.output.isNotEmpty()) {
        ToolResultCard(
          output = toolState.output,
          onCopy = { clipboard.setText(AnnotatedString(toolState.output)) },
          onSpeak = if (state.canSpeak) { { viewModel.speak(toolState.output) } } else null,
        )
      }
    }
  }
}

@Composable
private fun TranslateTool(running: Boolean, onTranslate: (String, String) -> Unit) {
  var text by remember { mutableStateOf("") }
  var lang by remember { mutableStateOf("English") }
  val languages =
    listOf("English", "Spanish", "French", "German", "Arabic", "Chinese", "Japanese", "Italian")
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Box(modifier = Modifier.padding(18.dp)) {
      if (text.isEmpty()) {
        Text(
          tr("tool.enter_text", "Enter text to translate"),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      BasicTextField(
        value = text,
        onValueChange = { text = it },
        textStyle =
          MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = gemmaBrush(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
      )
    }
  }
  Text(
    tr("tool.translate_to", "Translate to"),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    languages.forEach { l -> LangChip(label = l, selected = l == lang) { lang = l } }
  }
  GradientActionButton(
    text = tr("tool.translate", "Translate"),
    icon = Icons.Rounded.Translate,
    enabled = text.isNotBlank() && !running,
    onClick = { onTranslate(text, lang) },
  )
}

@Composable
private fun TranscribeTool(running: Boolean, supportsAudio: Boolean, onTranscribe: (ByteArray) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val recorder = remember { ChatAudioRecorder() }
  var isRecording by remember { mutableStateOf(false) }
  var level by remember { mutableStateOf(0f) }
  val permission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        recorder.start(scope) { level = it }
        isRecording = true
      }
    }
  fun toggle() {
    when {
      isRecording -> {
        val bytes = recorder.stop()
        isRecording = false
        level = 0f
        if (bytes.isNotEmpty()) onTranscribe(bytes)
      }
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED -> {
        recorder.start(scope) { level = it }
        isRecording = true
      }
      else -> permission.launch(Manifest.permission.RECORD_AUDIO)
    }
  }
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Text(
      tr("tool.transcribe_desc", "Record speech and Rin writes it down. Needs an audio-capable on-device model."),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    if (isRecording) {
      VoiceWaveform(level = level, modifier = Modifier.fillMaxWidth(0.8f))
    }
    // Big circular mic, gradient-filled while recording (matches the chat composer's mic).
    Box(
      contentAlignment = Alignment.Center,
      modifier =
        Modifier.size(80.dp)
          .clip(CircleShape)
          .background(
            if (isRecording) gemmaBrush()
            else SolidColor(MaterialTheme.colorScheme.surfaceVariant)
          )
          .clickable(enabled = !running) { toggle() },
    ) {
      Icon(
        if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
        contentDescription = if (isRecording) tr("tool.stop_transcribe_cd", "Stop & transcribe") else tr("tool.record_cd", "Record"),
        tint = if (isRecording) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(34.dp),
      )
    }
    Text(
      if (isRecording) tr("tool.tap_stop_transcribe", "Tap to stop & transcribe") else tr("tool.tap_record", "Tap to record"),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!supportsAudio) {
      Text(
        tr("tool.needs_audio_model", "Load a Gemma model with audio support to transcribe."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
    }
  }
}

/** A pill chip for the language selector, gradient-outlined when selected. */
@Composable
private fun LangChip(label: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(50),
    color =
      if (selected) MaterialTheme.colorScheme.primaryContainer
      else MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onClick() },
  ) {
    Text(
      label,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
      style = MaterialTheme.typography.labelLarge,
      color =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** A full-width gradient pill action button (greys out when disabled). */
@Composable
private fun GradientActionButton(
  text: String,
  icon: ImageVector,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      Modifier.fillMaxWidth()
        .height(52.dp)
        .clip(RoundedCornerShape(26.dp))
        .background(
          if (enabled) gemmaBrush()
          else SolidColor(MaterialTheme.colorScheme.surfaceVariant)
        )
        .clickable(enabled = enabled) { onClick() },
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        icon,
        contentDescription = null,
        tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
      Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Result card for a tool's output, with copy + read-aloud. */
@Composable
private fun ToolResultCard(output: String, onCopy: () -> Unit, onSpeak: (() -> Unit)?) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      SelectionContainer {
        Text(output, style = MaterialTheme.typography.bodyLarge)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
          Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = tr("common.copy", "Copy"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
        if (onSpeak != null) {
          IconButton(onClick = onSpeak, modifier = Modifier.size(36.dp)) {
            Icon(
              Icons.Rounded.VolumeUp,
              contentDescription = tr("common.read_aloud", "Read aloud"),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }
    }
  }
}
