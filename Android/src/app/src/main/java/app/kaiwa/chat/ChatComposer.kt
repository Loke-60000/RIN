// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.kaiwa.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun InputBar(
  input: String,
  onInputChange: (String) -> Unit,
  enabled: Boolean,
  streaming: Boolean,
  supportsImage: Boolean,
  supportsAudio: Boolean,
  listening: Boolean,
  partialTranscript: String,
  voiceLevel: Float,
  sttEnabled: Boolean,
  onVoiceStart: ((String) -> Unit) -> Unit,
  onVoiceStop: () -> Unit,
  onSend: (String, List<Bitmap>, List<ByteArray>) -> Unit,
  onStop: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val images = remember { mutableStateListOf<Bitmap>() }
  val audioClips = remember { mutableStateListOf<ByteArray>() }

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
  val recorder = remember { ChatAudioRecorder() }
  var isRecording by remember { mutableStateOf(false) }
  var recordLevel by remember { mutableStateOf(0f) }
  val voiceActive = listening || isRecording
  // The waveform follows the recorder's amplitude when recording, else the recogniser's RMS.
  val micLevel = if (isRecording) recordLevel else voiceLevel

  // One mic, capability-routed: audio-capable models "hear" the recording natively; otherwise the
  // built-in recognizer transcribes speech into the composer for the user to review/send.
  fun startMic() {
    if (supportsAudio) {
      recorder.start(scope) { recordLevel = it }
      isRecording = true
    } else {
      onVoiceStart(onInputChange)
    }
  }

  val micPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) startMic()
    }

  fun toggleMic() {
    when {
      listening -> onVoiceStop()
      isRecording -> {
        val bytes = recorder.stop()
        isRecording = false
        recordLevel = 0f
        if (bytes.isNotEmpty()) audioClips.add(bytes)
      }
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED -> startMic()
      else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  fun submit() {
    if (isRecording) {
      val bytes = recorder.stop()
      isRecording = false
      recordLevel = 0f
      if (bytes.isNotEmpty()) audioClips.add(bytes)
    }
    if (input.isNotBlank() || images.isNotEmpty() || audioClips.isNotEmpty()) {
      onSend(input, images.toList(), audioClips.toList())
      onInputChange("")
      images.clear()
      audioClips.clear()
    }
  }

  val hasContent = input.isNotBlank() || images.isNotEmpty() || audioClips.isNotEmpty()

  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
    // Gemini-style live spectrogram across the full width: spreads out from the centre when the mic
    // opens and collapses back into the centre when it closes, reacting to the mic level throughout.
    AnimatedVisibility(
      visible = voiceActive,
      enter =
        expandHorizontally(
          animationSpec = tween(320, easing = FastOutSlowInEasing),
          expandFrom = Alignment.CenterHorizontally,
        ) + fadeIn(tween(320)),
      exit =
        shrinkHorizontally(
          animationSpec = tween(280, easing = FastOutSlowInEasing),
          shrinkTowards = Alignment.CenterHorizontally,
        ) + fadeOut(tween(200)),
    ) {
      VoiceWaveform(level = micLevel, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
    }
    if (images.isNotEmpty()) {
      val imgScroll = rememberScrollState()
      Row(
        modifier =
          Modifier.fillMaxWidth()
            // Fade the scroll edges to transparent instead of a hard cut-off.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
              drawContent()
              val w = 28.dp.toPx()
              if (imgScroll.value < imgScroll.maxValue) {
                drawRect(
                  brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                  blendMode = BlendMode.DstIn,
                )
              }
              if (imgScroll.value > 0) {
                drawRect(
                  brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w),
                  blendMode = BlendMode.DstIn,
                )
              }
            }
            .horizontalScroll(imgScroll)
            .padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        images.forEachIndexed { i, bmp ->
          // Pad the box so the delete badge can sit just outside the thumbnail's corner.
          Box(modifier = Modifier.padding(top = 6.dp, end = 6.dp)) {
            ComposeImage(
              bitmap = bmp.asImageBitmap(),
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier =
                Modifier.size(64.dp)
                  .clip(RoundedCornerShape(14.dp))
                  .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            // High-contrast circular delete badge — visible on any image, comfortable tap target.
            Box(
              contentAlignment = Alignment.Center,
              modifier =
                Modifier.align(Alignment.TopEnd)
                  .offset(x = 6.dp, y = (-6).dp)
                  .size(24.dp)
                  .clip(CircleShape)
                  .background(Color.Black.copy(alpha = 0.6f))
                  .clickable { images.removeAt(i) },
            ) {
              Icon(
                Icons.Rounded.Close,
                contentDescription = tr("chat.remove_cd", "Remove"),
                tint = Color.White,
                modifier = Modifier.size(15.dp),
              )
            }
          }
        }
      }
    }

    // The Gemini-style input pill.
    Surface(
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 3.dp,
      shadowElevation = 2.dp,
      modifier = Modifier.fillMaxWidth(),
    ) {
      // Gemini-style composer: the message sits on its own line, controls in a row beneath it.
      Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        val shownText = if (voiceActive) partialTranscript else input
        // When a recorded clip is staged, the composer becomes a clean voice-message state (no text
        // field / + / mic clutter) and reverts to normal once it's sent or removed.
        val audioPending = audioClips.isNotEmpty() && !voiceActive
        Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
          if (audioPending) {
            ComposerAudioChip(audio = audioClips.first(), onDelete = { audioClips.clear() })
          } else {
            if (shownText.isEmpty()) {
              Text(
                if (voiceActive) tr("chat.listening", "Listening…") else tr("chat.message_hint", "Message Rin"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            BasicTextField(
              value = shownText,
              onValueChange = onInputChange,
              // Always typeable (so you can compose while the model loads); only sending is gated.
              // Locked while the mic is live — the field mirrors the transcript instead.
              enabled = !streaming && !voiceActive,
              textStyle =
                MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
              cursorBrush = gemmaBrush(),
              maxLines = 8,
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
              keyboardActions = KeyboardActions(onSend = { submit() }),
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
          if (!audioPending && supportsImage) {
            IconButton(onClick = { imagePicker.launch("image/*") }, enabled = enabled, modifier = Modifier.size(44.dp)) {
              Icon(Icons.Rounded.Add, contentDescription = tr("chat.attach_image_cd", "Attach image"), modifier = Modifier.size(26.dp))
            }
          }
          Spacer(Modifier.weight(1f))
          // Single Gemini-style mic — only shown when it can actually do something: record for an
          // audio-capable model, or transcribe via the system recogniser when voice typing is on.
          if (!audioPending && (supportsAudio || sttEnabled)) {
            MicButton(active = voiceActive, enabled = !streaming, onClick = { toggleMic() })
            Spacer(Modifier.width(4.dp))
          }
          // Send / Stop button — stop (square) while generating, gradient up-arrow to send.
          val active = streaming || (enabled && hasContent)
          Box(
            contentAlignment = Alignment.Center,
            modifier =
              Modifier.size(48.dp)
                .clip(CircleShape)
                .background(
                  if (active) gemmaBrush()
                  else
                    Brush.linearGradient(
                      listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.surfaceVariant,
                      )
                    )
                )
                .clickable(enabled = active) { if (streaming) onStop() else submit() },
          ) {
            Icon(
              if (streaming) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
              contentDescription = if (streaming) tr("chat.stop_cd", "Stop") else tr("chat.send_cd", "Send"),
              tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(22.dp),
            )
          }
        }
      }
    }
  }
}

/**
 * Gemini-style live audio spectrogram: a centered row of gradient bars whose heights follow the
 * live mic [level] (0..1). A per-bar shimmer keeps it alive at silence; a smoothed level makes the
 * whole shape rise and fall with the voice. Tallest in the middle, tapering to the edges.
 */
@Composable
internal fun VoiceWaveform(level: Float, modifier: Modifier = Modifier) {
  val barCount = 21
  // A sine window makes the centre bars react most and taper smoothly to the quiet edges.
  val weights = remember {
    List(barCount) { i -> 0.35f + 0.65f * kotlin.math.sin(Math.PI * i / (barCount - 1)).toFloat() }
  }
  val smoothed by
    animateFloatAsState(
      targetValue = level.coerceIn(0f, 1f),
      animationSpec = tween(110, easing = FastOutSlowInEasing),
      label = "level",
    )
  val transition = rememberInfiniteTransition(label = "wave")
  val brush = gemmaBrush()
  Row(
    modifier = modifier.height(44.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    weights.forEachIndexed { i, w ->
      val shimmer by
        transition.animateFloat(
          initialValue = 0.8f,
          targetValue = 1.2f,
          animationSpec =
            infiniteRepeatable(
              animation = tween(durationMillis = 440 + (i % 5) * 120, easing = FastOutSlowInEasing),
              repeatMode = RepeatMode.Reverse,
            ),
          label = "shimmer$i",
        )
      // Idle floor (×shimmer) keeps the bars gently breathing at silence; voice loudness adds height.
      val h = ((0.18f + w * smoothed) * shimmer).coerceIn(0.08f, 1f)
      Box(
        modifier =
          Modifier.width(4.dp).fillMaxHeight(h).clip(RoundedCornerShape(50)).background(brush)
      )
    }
  }
}

/**
 * The composer's voice-message state: the staged recording becomes a small player — tap to
 * play/pause and listen before sending, with a remove button. Sending is the composer's send arrow.
 */
@Composable
private fun ComposerAudioChip(audio: ByteArray, onDelete: () -> Unit) {
  val context = LocalContext.current
  var playing by remember { mutableStateOf(false) }
  val player = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
  // A throwaway cache copy so MediaPlayer can play the staged WAV bytes.
  val file =
    remember(audio) {
      java.io.File(context.cacheDir, "voice_preview.wav").apply { writeBytes(audio) }
    }
  DisposableEffect(audio) {
    onDispose {
      player.value?.release()
      player.value = null
    }
  }
  fun toggle() {
    val p = player.value
    if (playing && p != null) {
      runCatching { p.stop() }
      p.release()
      player.value = null
      playing = false
    } else {
      runCatching {
        android.media.MediaPlayer().apply {
          setDataSource(file.absolutePath)
          setOnCompletionListener {
            release()
            player.value = null
            playing = false
          }
          prepare()
          start()
          player.value = this
          playing = true
        }
      }
    }
  }
  Surface(
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.primaryContainer,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
    ) {
      // Play / pause the recording.
      Box(
        contentAlignment = Alignment.Center,
        modifier =
          Modifier.size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable { toggle() },
      ) {
        Icon(
          if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
          contentDescription = if (playing) tr("chat.stop_cd", "Stop") else tr("chat.play_recording_cd", "Play recording"),
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.size(22.dp),
        )
      }
      Spacer(Modifier.width(10.dp))
      Icon(
        Icons.Rounded.GraphicEq,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
        modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.width(8.dp))
      Text(
        tr("chat.voice_message", "Voice message"),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.weight(1f),
      )
      IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
        Icon(
          Icons.Rounded.Close,
          contentDescription = tr("chat.remove_voice_cd", "Remove voice message"),
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

/** Gemini-style voice button: a plain mic at rest; a filled accent stop button while live. */
@Composable
private fun MicButton(active: Boolean, enabled: Boolean, onClick: () -> Unit) {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
    Box(
      contentAlignment = Alignment.Center,
      modifier =
        Modifier.size(40.dp)
          .clip(CircleShape)
          .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
          .clickable(enabled = enabled) { onClick() },
    ) {
      Icon(
        if (active) Icons.Rounded.Stop else Icons.Rounded.Mic,
        contentDescription = if (active) tr("chat.stop_listening_cd", "Stop listening") else tr("chat.voice_cd", "Voice"),
        tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
      )
    }
  }
}
