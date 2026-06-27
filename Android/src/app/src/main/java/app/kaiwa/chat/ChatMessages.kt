// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kaiwa.i18n.tr
import app.kaiwa.ui.common.MarkdownText

@Composable
internal fun MessageList(
  messages: List<ChatMessage>,
  streaming: Boolean,
  searchingWeb: Boolean,
  streamingText: String,
  streamingThinking: String,
  onRetry: () -> Unit,
  onSpeak: ((String) -> Unit)?,
  editingTs: Long?,
  selectedTs: Long?,
  onSelect: (ChatMessage) -> Unit,
  onStartEdit: (ChatMessage) -> Unit,
  onCancelEdit: () -> Unit,
  onSubmitEdit: (ChatMessage, String) -> Unit,
  contentPadding: PaddingValues,
) {
  val listState = rememberLazyListState()
  val clipboard = LocalClipboardManager.current
  // Normal top-anchored layout: expanding a thoughts block pushes the answer DOWN (header stays
  // put), not up. Streaming still follows the bottom by scrolling to the trailing spacer (pinning
  // the bottom) — only while the user is already at the bottom, so reading history isn't yanked.
  val atBottom by remember {
    derivedStateOf {
      val layout = listState.layoutInfo
      val last = layout.visibleItemsInfo.lastOrNull()
      last == null || last.index >= layout.totalItemsCount - 1
    }
  }
  fun lastIndex() = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
  // New turn (message added) → jump to the bottom.
  LaunchedEffect(messages.size) { listState.scrollToItem(lastIndex()) }
  // While streaming, keep the bottom pinned as tokens arrive (no effect on a manual thoughts toggle).
  LaunchedEffect(streaming, streamingText.length, streamingThinking.length) {
    if (streaming && atBottom) listState.scrollToItem(lastIndex())
  }
  LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize(),
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    itemsIndexed(messages, key = { _, m -> "${m.role}-${m.ts}" }) { index, msg ->
      if (msg.role == ChatRole.USER) {
        UserBubble(
          msg = msg,
          editing = editingTs == msg.ts,
          selected = selectedTs == msg.ts,
          showEdit = !streaming,
          onSelect = { onSelect(msg) },
          onStartEdit = { onStartEdit(msg) },
          onCancelEdit = onCancelEdit,
          onSubmitEdit = { onSubmitEdit(msg, it) },
        )
      } else {
        val (think, answer) = remember(msg.text, msg.thinking) { splitThinking(msg.text, msg.thinking) }
        AgentBubble(text = answer, thinking = think, thinkingInProgress = false)
        if (msg.sources.isNotEmpty()) SourcePills(msg.sources)
        if (!streaming) {
          MessageActions(
            onCopy = { clipboard.setText(AnnotatedString(answer)) },
            onRetry = if (index == messages.lastIndex) onRetry else null,
            onSpeak = onSpeak?.let { f -> { f(answer) } },
          )
        }
      }
    }
    if (streaming) {
      item(key = "streaming") {
        val (think, ans) =
          remember(streamingText, streamingThinking) { splitThinking(streamingText, streamingThinking) }
        if (searchingWeb) {
          SearchingRow()
        } else {
          // While only reasoning streams, show the expanded "Thinking…"; once the answer starts it
          // collapses to a "Thoughts" chip and the answer flows in below it. The red typing cursor
          // marks where the reply is being written (and blinks alone before the first token).
          AgentBubble(
            text = ans,
            thinking = think,
            thinkingInProgress = ans.isEmpty(),
            streaming = true,
          )
        }
      }
    }
    item(key = "tail") { Spacer(Modifier.height(8.dp)) }
  }
}

/** "Searching online…" status shown while a web search runs, before the answer streams. */
@Composable
private fun SearchingRow() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    AnimatedGemmaMark(24.dp)
    Text(
      tr("chat.searching_online", "Searching online…"),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Gray clickable pills for the web-search sources used to answer, like the Gemini app. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcePills(sources: List<SearchResult>) {
  val uriHandler = LocalUriHandler.current
  FlowRow(
    modifier = Modifier.fillMaxWidth().padding(start = 34.dp, top = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    for (s in sources) {
      if (s.url.isBlank()) continue
      Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier =
          Modifier.clip(RoundedCornerShape(50)).clickable {
            runCatching { uriHandler.openUri(s.url) }
          },
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
          Icon(
            Icons.Rounded.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
          )
          Text(
            sourceHost(s.url),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

/** The bare host of a URL (without "www."), used as a short source label. */
private fun sourceHost(url: String): String =
  runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }
    ?: url.removePrefix("https://").removePrefix("http://").substringBefore("/").take(30)

@Composable
private fun MessageActions(onCopy: () -> Unit, onRetry: (() -> Unit)?, onSpeak: (() -> Unit)?) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 30.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
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
    if (onRetry != null) {
      IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
        Icon(
          Icons.Rounded.Refresh,
          contentDescription = tr("chat.regenerate_cd", "Regenerate"),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

/** A voice-message bubble for a sent audio clip — tap to play/stop the saved WAV. */
@Composable
private fun AudioMessageBubble(path: String) {
  var playing by remember { mutableStateOf(false) }
  val player = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
  DisposableEffect(path) {
    onDispose {
      player.value?.release()
      player.value = null
    }
  }
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.primary,
    modifier =
      Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(20.dp)).clickable {
        val p = player.value
        if (playing && p != null) {
          runCatching { p.stop() }
          p.release()
          player.value = null
          playing = false
        } else {
          runCatching {
            android.media.MediaPlayer().apply {
              setDataSource(path)
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
      },
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
      Icon(
        if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
        contentDescription = if (playing) tr("chat.stop_cd", "Stop") else tr("chat.play_voice_cd", "Play voice message"),
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(22.dp),
      )
      Icon(
        Icons.Rounded.GraphicEq,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
        modifier = Modifier.size(20.dp),
      )
      Text(
        tr("chat.voice_message", "Voice message"),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onPrimary,
      )
    }
  }
}

@Composable
private fun UserBubble(
  msg: ChatMessage,
  editing: Boolean,
  selected: Boolean,
  showEdit: Boolean,
  onSelect: () -> Unit,
  onStartEdit: () -> Unit,
  onCancelEdit: () -> Unit,
  onSubmitEdit: (String) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
    msg.imagePaths.forEach { path ->
      val bmp = remember(path) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
      if (bmp != null) {
        ComposeImage(
          bitmap = bmp.asImageBitmap(),
          contentDescription = null,
          contentScale = androidx.compose.ui.layout.ContentScale.Fit,
          modifier =
            Modifier.padding(bottom = 6.dp).widthIn(max = 240.dp).clip(RoundedCornerShape(16.dp)),
        )
      }
    }
    msg.audioPath?.let { AudioMessageBubble(it) }
    if (editing) {
      // The bubble itself becomes an editable field; saving rewinds and resends from here.
      var text by remember(msg.ts) { mutableStateOf(msg.text) }
      Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.End) {
          OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onCancelEdit) { Text(tr("common.cancel", "Cancel")) }
            androidx.compose.material3.Button(
              onClick = { if (text.isNotBlank()) onSubmitEdit(text) },
              enabled = text.isNotBlank(),
            ) {
              Text(tr("chat.update", "Update"))
            }
          }
        }
      }
    } else {
      if (msg.text.isNotEmpty()) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          modifier =
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = showEdit, onClick = onSelect),
        ) {
          Text(
            msg.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
          )
        }
      }
      // Reserve the edit button's footprint under every message so revealing it on tap doesn't
      // push content; only the icon itself fades in/out with selection.
      Box(modifier = Modifier.size(36.dp)) {
        if (showEdit && selected) {
          IconButton(onClick = onStartEdit, modifier = Modifier.size(36.dp)) {
            Icon(
              Icons.Rounded.Edit,
              contentDescription = tr("chat.edit_message_cd", "Edit message"),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AgentBubble(
  text: String,
  thinking: String,
  thinkingInProgress: Boolean,
  streaming: Boolean = false,
) {
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (thinking.isNotBlank()) ThinkingBlock(thinking = thinking, inProgress = thinkingInProgress)
    // Render markdown live as it streams (bottom-anchored list absorbs the reflow without jumping).
    if (text.isNotBlank()) GemmaMarkdown(text = text)
    // The logo's red bar doubles as the typing cursor — shown while the answer streams (and at the
    // very start), but not during the pure "Thinking…" phase, which has its own pulse.
    if (streaming && (text.isNotBlank() || thinking.isBlank())) TypingCursor()
  }
}

/** The kaiwa logo's red accent bar, reused as a blinking "typing" cursor while a reply streams. */
@Composable
private fun TypingCursor() {
  val transition = rememberInfiniteTransition(label = "cursor")
  val alpha by
    transition.animateFloat(
      initialValue = 1f,
      targetValue = 0.12f,
      animationSpec =
        infiniteRepeatable(tween(640, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "cursorAlpha",
    )
  Box(
    modifier =
      Modifier.padding(top = 2.dp)
        .size(width = 3.dp, height = 18.dp)
        .graphicsLayer { this.alpha = alpha }
        .clip(RoundedCornerShape(2.dp))
        .background(Color(0xFFE8503A))
  )
}

/** A collapsible "thinking" block: pulses while the model reasons; tap to read the reasoning. */
@Composable
private fun ThinkingBlock(thinking: String, inProgress: Boolean) {
  var expanded by remember { mutableStateOf(inProgress) }
  // Auto-expand while reasoning, auto-collapse when the answer begins.
  LaunchedEffect(inProgress) { expanded = inProgress }

  val labelAlpha =
    if (inProgress) {
      val t = rememberInfiniteTransition(label = "thinkPulse")
      val a by
        t.animateFloat(
          0.45f,
          1f,
          infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
          "thinkAlpha",
        )
      a
    } else 1f

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        if (inProgress) tr("chat.thinking", "Thinking…") else tr("chat.thoughts", "Thoughts"),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.graphicsLayer { alpha = labelAlpha },
      )
      Icon(
        if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
      )
    }
    AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
      val line = MaterialTheme.colorScheme.outlineVariant
      Box(
        modifier =
          Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp).drawBehind {
            drawLine(
              color = line,
              start = Offset(0f, 0f),
              end = Offset(0f, size.height),
              strokeWidth = 3f,
            )
          }
      ) {
        Text(
          thinking,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 12.dp),
        )
      }
    }
  }
}

private sealed interface MdSegment {
  data class Prose(val text: String) : MdSegment

  data class Code(val lang: String, val code: String) : MdSegment
}

/** Splits text into prose and fenced-code segments. An unterminated fence (still streaming) is
 * treated as code so the raw ``` never flashes on screen. */
private fun parseMarkdownSegments(text: String): List<MdSegment> {
  val segments = mutableListOf<MdSegment>()
  val prose = StringBuilder()
  val code = StringBuilder()
  var inCode = false
  var lang = ""
  for (line in text.split("\n")) {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("```")) {
      if (!inCode) {
        if (prose.isNotBlank()) segments.add(MdSegment.Prose(prose.toString().trim()))
        prose.clear()
        inCode = true
        lang = trimmed.removePrefix("```").trim()
        code.clear()
      } else {
        segments.add(MdSegment.Code(lang, code.toString().trimEnd('\n')))
        inCode = false
      }
    } else if (inCode) {
      code.append(line).append("\n")
    } else {
      prose.append(line).append("\n")
    }
  }
  if (inCode) segments.add(MdSegment.Code(lang, code.toString().trimEnd('\n')))
  else if (prose.isNotBlank()) segments.add(MdSegment.Prose(prose.toString().trim()))
  return segments
}

/** Renders assistant text as Gemini-style markdown: prose via [MarkdownText], fenced code as dark
 * rounded cards with a language label and a copy button. */
@Composable
private fun GemmaMarkdown(text: String, modifier: Modifier = Modifier) {
  val segments = remember(text) { parseMarkdownSegments(text) }
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    segments.forEach { seg ->
      when (seg) {
        is MdSegment.Prose ->
          if (seg.text.isNotBlank()) {
            MarkdownText(text = seg.text, textColor = MaterialTheme.colorScheme.onSurface)
          }
        is MdSegment.Code -> CodeCard(lang = seg.lang, code = seg.code)
      }
    }
  }
}

@Composable
private fun CodeCard(lang: String, code: String) {
  val clipboard = LocalClipboardManager.current
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = Color(0xFF1B1B1F),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp),
      ) {
        Text(
          lang.ifBlank { "code" },
          color = Color(0xFFCFCFD4),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.weight(1f),
        )
        IconButton(
          onClick = { clipboard.setText(AnnotatedString(code)) },
          modifier = Modifier.size(32.dp),
        ) {
          Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = tr("chat.copy_code_cd", "Copy code"),
            tint = Color(0xFFCFCFD4),
            modifier = Modifier.size(18.dp),
          )
        }
      }
      SelectionContainer {
        Text(
          code,
          color = Color(0xFFF1F1F4),
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodyMedium,
          modifier =
            Modifier.horizontalScroll(rememberScrollState())
              .padding(horizontal = 16.dp, vertical = 6.dp),
        )
      }
    }
  }
}
