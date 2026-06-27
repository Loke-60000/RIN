// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.kaiwa.i18n.tr
import app.kaiwa.ui.common.MarkdownText
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Gemini-styled assistant overlay: a transparent backdrop (the screen/wallpaper shows through)
 * with floating, bottom-anchored rounded cards and a pill-shaped input — hosted by
 * [AssistantOverlayActivity].
 */
@Composable
fun AssistantPopup(viewModel: AssistantViewModel, onClose: () -> Unit, onOpenApp: () -> Unit) {
  val state by viewModel.uiState.collectAsState()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var input by remember { mutableStateOf("") }
  // Screen reading is opt-in (off by default) and only for vision-capable models.
  var useScreen by remember(state.modelSupportsVision) { mutableStateOf(false) }
  // True while a one-shot screen capture (consent + single frame) is in flight.
  var capturingScreen by remember { mutableStateOf(false) }
  // When a fresh screenshot lands (ScreenCaptureManager bumps), switch to "Seeing your screen".
  val captureVersion = ScreenCaptureManager.version
  LaunchedEffect(captureVersion) {
    if (capturingScreen && ScreenContextHolder.screenshot != null) {
      useScreen = true
      capturingScreen = false
    }
  }
  // The MediaProjection consent runs from this (the popup's) activity so it returns straight back
  // here; on grant we fire the one-shot capture service, which bumps ScreenCaptureManager above.
  val screenCaptureLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
        ScreenCaptureService.start(context, result.resultCode, result.data!!)
      } else {
        capturingScreen = false
      }
    }
  val images = remember { mutableStateListOf<Bitmap>() }
  val imagePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      if (uri != null) {
        scope.launch {
          val bmp =
            withContext(Dispatchers.IO) {
              runCatching {
                  val source = ImageDecoder.createSource(context.contentResolver, uri)
                  ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                  }
                }
                .getOrNull()
            }
          if (bmp != null) images.add(bmp)
        }
      }
    }
  val micPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) viewModel.startVoiceInput(useScreen)
    }
  val onMic: () -> Unit = {
    if (state.listening) {
      viewModel.stopVoiceInput()
    } else if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      viewModel.startVoiceInput(useScreen)
    } else {
      micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  val busy = state.phase == AssistantPhase.THINKING || state.phase == AssistantPhase.INITIALIZING
  val hasAnswer = state.response.isNotEmpty() || state.lastPrompt.isNotEmpty()

  // Transparent backdrop — tapping outside the cards dismisses.
  Box(
    modifier =
      Modifier.fillMaxSize()
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onClose,
        )
  ) {
    Column(
      modifier =
        Modifier.align(Alignment.BottomCenter)
          .fillMaxWidth()
          .navigationBarsPadding()
          .imePadding()
          .padding(horizontal = 12.dp, vertical = 12.dp)
          // Swallow taps inside the cards so they don't dismiss the overlay.
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
          ),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      horizontalAlignment = Alignment.Start,
    ) {
      when (state.phase) {
        AssistantPhase.NO_MODEL -> NoModelCard(onOpenApp = onOpenApp)
        AssistantPhase.ERROR ->
          AnswerCard(
            text = state.errorMessage.ifEmpty { tr("assistant.something_wrong", "Something went wrong.") },
            isError = true,
            actions = state.performedActions,
            onOpenApp = onOpenApp,
          )
        else -> {
          // Answer card appears once there's a response (streaming or done).
          if (state.response.isNotEmpty()) {
            AnswerCard(
              text = state.response,
              isError = false,
              actions = state.performedActions,
              onOpenApp = onOpenApp,
            )
          }
        }
      }

      // While generating with nothing yet, show a compact status pill instead of the input.
      if (busy && state.response.isEmpty()) {
        StatusPill(
          text =
            if (state.phase == AssistantPhase.INITIALIZING) tr("assistant.waking_model", "Waking up the model…") else tr("assistant.one_moment", "One moment…")
        )
      } else if (state.phase != AssistantPhase.NO_MODEL) {
        if (state.modelSupportsVision && state.screenReadingEnabled) {
          ScreenChip(
            checked = useScreen,
            capturing = capturingScreen,
            onCheckedChange = { want ->
              when {
                !want -> useScreen = false
                ScreenContextHolder.screenshot != null -> useScreen = true
                else -> {
                  // One-shot: ask for the capture grant, grab a single frame, then release.
                  capturingScreen = true
                  val mpm =
                    context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                      as android.media.projection.MediaProjectionManager
                  screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                }
              }
            },
          )
        }
        InputPill(
          input = input,
          onInputChange = { input = it },
          enabled = !busy,
          showAttach = state.modelSupportsVision,
          images = images,
          listening = state.listening,
          partialTranscript = state.partialTranscript,
          voiceReplies = state.voiceReplies,
          onAttach = { imagePicker.launch("image/*") },
          onRemoveImage = { images.removeAt(it) },
          onMic = onMic,
          onToggleVoice = { viewModel.toggleVoiceReplies() },
          onSend = {
            val text = input.trim()
            if (text.isNotEmpty() || images.isNotEmpty()) {
              viewModel.ask(text, useScreen, images.map { it.toPng() })
              input = ""
              images.clear()
            }
          },
          onOpenApp = onOpenApp,
        )
      }
    }
  }
}

/** A floating white rounded card, the building block of the overlay. */
@Composable
private fun FloatingCard(
  modifier: Modifier = Modifier,
  shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(28.dp),
  content: @Composable () -> Unit,
) {
  Surface(
    shape = shape,
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = 6.dp,
    tonalElevation = 0.dp,
    modifier = modifier,
  ) {
    content()
  }
}

@Composable
private fun AnswerCard(
  text: String,
  isError: Boolean,
  actions: List<String>,
  onOpenApp: () -> Unit,
) {
  val clipboard = LocalClipboardManager.current
  FloatingCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier =
        Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      DragHandle()
      // Selectable so the user can highlight part of the reply; markdown-rendered like the chat.
      SelectionContainer {
        if (isError) {
          Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
          )
        } else {
          MarkdownText(text = text, textColor = MaterialTheme.colorScheme.onSurface)
        }
      }
      for (a in actions) {
        Text(
          "• $a",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(
          onClick = { clipboard.setText(AnnotatedString(text)) },
          modifier = Modifier.size(36.dp),
        ) {
          Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = tr("common.copy", "Copy"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
        IconButton(onClick = onOpenApp, modifier = Modifier.size(36.dp)) {
          Icon(
            Icons.Rounded.OpenInNew,
            contentDescription = tr("assistant.continue_in_gemma", "Continue in Rin"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun StatusPill(text: String) {
  FloatingCard(shape = RoundedCornerShape(30.dp), modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
  }
}

@Composable
private fun InputPill(
  input: String,
  onInputChange: (String) -> Unit,
  enabled: Boolean,
  showAttach: Boolean,
  images: List<Bitmap>,
  listening: Boolean,
  partialTranscript: String,
  voiceReplies: Boolean,
  onAttach: () -> Unit,
  onRemoveImage: (Int) -> Unit,
  onMic: () -> Unit,
  onToggleVoice: () -> Unit,
  onSend: () -> Unit,
  onOpenApp: () -> Unit,
) {
  FloatingCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
    // Gemini-style composer: attachments + message on top, controls in a row beneath.
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
      if (images.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          images.forEachIndexed { i, bmp ->
            Box {
              Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
              )
              IconButton(
                onClick = { onRemoveImage(i) },
                modifier = Modifier.size(22.dp).align(Alignment.TopEnd),
              ) {
                Icon(Icons.Rounded.Close, contentDescription = tr("chat.remove_cd", "Remove"), modifier = Modifier.size(16.dp))
              }
            }
          }
        }
      }
      val shownText = if (listening) partialTranscript else input
      Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
        if (shownText.isEmpty()) {
          Text(
            if (listening) tr("chat.listening", "Listening…") else tr("assistant.ask_gemma", "Ask Rin"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        BasicTextField(
          value = shownText,
          onValueChange = onInputChange,
          // Locked while the mic is live — the field mirrors the transcript instead.
          enabled = enabled && !listening,
          textStyle =
            MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          maxLines = 8,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = { onSend() }),
          modifier = Modifier.fillMaxWidth(),
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (showAttach) {
          IconButton(onClick = onAttach, enabled = enabled, modifier = Modifier.size(44.dp)) {
            Icon(
              Icons.Rounded.Add,
              contentDescription = tr("chat.attach_image_cd", "Attach image"),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(26.dp),
            )
          }
        }
        IconButton(onClick = onToggleVoice, modifier = Modifier.size(44.dp)) {
          Icon(
            if (voiceReplies) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
            contentDescription = if (voiceReplies) tr("assistant.voice_replies_on", "Voice replies on") else tr("assistant.voice_replies_off", "Voice replies off"),
            tint =
              if (voiceReplies) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
          )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenApp, modifier = Modifier.size(44.dp)) {
          Icon(
            Icons.Rounded.OpenInNew,
            contentDescription = tr("assistant.open_gemma", "Open Rin"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
          )
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onMic, enabled = enabled, modifier = Modifier.size(44.dp)) {
          Icon(
            if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
            contentDescription = if (listening) tr("chat.stop_listening_cd", "Stop listening") else tr("assistant.speak_cd", "Speak"),
            tint =
              if (listening) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
          )
        }
        Spacer(Modifier.width(4.dp))
        FilledIconButton(
          onClick = onSend,
          enabled = enabled && (input.isNotBlank() || images.isNotEmpty()),
          modifier = Modifier.size(48.dp),
        ) {
          Icon(Icons.Rounded.ArrowUpward, contentDescription = tr("chat.send_cd", "Send"), modifier = Modifier.size(22.dp))
        }
      }
    }
  }
}

private fun Bitmap.toPng(): ByteArray {
  val out = ByteArrayOutputStream()
  compress(Bitmap.CompressFormat.PNG, 100, out)
  return out.toByteArray()
}

/** A compact pill toggle (above the input) that grabs one screenshot for vision models on tap. */
@Composable
private fun ScreenChip(checked: Boolean, capturing: Boolean, onCheckedChange: (Boolean) -> Unit) {
  val bg =
    if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
  val onBg =
    if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
  Surface(
    shape = CircleShape,
    color = bg,
    shadowElevation = 6.dp,
    modifier =
      Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = !capturing,
        onClick = { onCheckedChange(!checked) },
      ),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (capturing) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = onBg)
      } else {
        Icon(
          Icons.Rounded.Smartphone,
          contentDescription = null,
          tint = if (checked) onBg else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
      }
      Text(
        when {
          capturing -> tr("assistant.capturing_screen", "Capturing…")
          checked -> tr("assistant.seeing_screen", "Seeing your screen")
          else -> tr("assistant.add_screen", "See screen")
        },
        style = MaterialTheme.typography.labelLarge,
        color = onBg,
      )
    }
  }
}

@Composable
private fun NoModelCard(onOpenApp: () -> Unit) {
  FloatingCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      DragHandle()
      Text(
        tr("assistant.no_model", "No model is ready yet. Open Rin to download a model or pick a cloud model, then come back."),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = onOpenApp) { Text(tr("assistant.open_gemma", "Open Rin")) }
    }
  }
}

@Composable
private fun DragHandle() {
  Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
      modifier = Modifier.size(width = 32.dp, height = 4.dp),
    ) {}
  }
}
