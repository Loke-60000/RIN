// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kaiwa.i18n.tr
import app.kaiwa.ui.common.accentAuroraLight
import app.kaiwa.ui.common.tos.AppTosDialog
import app.kaiwa.ui.common.tos.TosViewModel
import app.kaiwa.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OkGemmaChatScreen(
  viewModel: MainChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel,
) {
  val state by viewModel.uiState.collectAsState()
  val mmState by modelManagerViewModel.uiState.collectAsState()
  // A pending MCP tool call blocks its calling thread until the user answers here.
  val permissionRequest by viewModel.permissionRequest.collectAsState()
  permissionRequest?.let { req ->
    McpToolPermissionDialog(
      toolName = req.toolName,
      argument = req.argument,
      onResult = { viewModel.resolvePermission(it) },
    )
  }
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  var showSettings by remember { mutableStateOf(false) }
  // Which settings sub-page to open at (e.g. "llm" when "manage models" is tapped from the picker).
  var settingsPage by remember { mutableStateOf("main") }
  fun openSettings(page: String) {
    settingsPage = page
    showSettings = true
  }
  // Non-null when a tool page (transcribe / translate) is open from the drawer.
  var showTool by remember { mutableStateOf<String?>(null) }
  var showTos by remember { mutableStateOf(!tosViewModel.getIsTosAccepted()) }
  var pendingDeleteId by remember { mutableStateOf<String?>(null) }
  var pendingRename by remember { mutableStateOf<Conversation?>(null) }
  var input by remember { mutableStateOf("") }
  // Timestamp of the user message currently being edited inline (its bubble becomes a text field).
  var editingTs by remember { mutableStateOf<Long?>(null) }
  // Timestamp of the user message tapped to reveal its edit affordance.
  var selectedTs by remember { mutableStateOf<Long?>(null) }
  // Measured height of the bottom input area, so the message list can scroll behind it.
  var inputHeightPx by remember { mutableStateOf(0) }
  val density = LocalDensity.current

  val allowlistDone =
    !mmState.loadingModelAllowlist || mmState.loadingModelAllowlistError.isNotEmpty()
  LaunchedEffect(allowlistDone, mmState.modelDownloadStatus, showTos) {
    if (allowlistDone && !showTos) {
      val downloaded = modelManagerViewModel.getAllDownloadedModels()
      val preferred = viewModel.preferredModelName()
      val selected = modelManagerViewModel.getSelectedModel()
      val model =
        downloaded.firstOrNull { it.name == preferred }
          ?: downloaded.firstOrNull { it.name == selected?.name }
          ?: downloaded.firstOrNull()
      viewModel.autoSelect(model)
    }
  }

  // Adopt a transcript handed over from the assistant popup ("Open Rin" continues that chat).
  LaunchedEffect(Unit) {
    app.kaiwa.assistant.PopupHandoff.consume()?.let {
      viewModel.adoptPopupConversation(it)
    }
  }

  if (showTos) {
    AppTosDialog(
      onTosAccepted = {
        showTos = false
        tosViewModel.acceptTos()
      }
    )
    return
  }

  if (showSettings) {
    ChatSettings(
      viewModel = viewModel,
      modelManagerViewModel = modelManagerViewModel,
      state = state,
      onBack = { showSettings = false },
      initialPage = settingsPage,
    )
    return
  }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ChatDrawer(
        state = state,
        activeMode = showTool ?: "chat",
        onNewChat = {
          viewModel.clearTool()
          showTool = null
          viewModel.newChat()
          scope.launch { drawerState.close() }
        },
        onOpenConversation = {
          viewModel.clearTool()
          showTool = null
          viewModel.loadConversation(it)
          scope.launch { drawerState.close() }
        },
        onDeleteConversation = { pendingDeleteId = it },
        onRenameConversation = { pendingRename = it },
        onOpenTool = {
          viewModel.clearTool()
          showTool = it
          scope.launch { drawerState.close() }
        },
        onOpenSettings = {
          openSettings("main")
          scope.launch { drawerState.close() }
        },
      )
    },
  ) {
    // A drawer destination other than chat: render its tool page. The hamburger opens the drawer;
    // system back returns to chat.
    showTool?.let { tool ->
      BackHandler { viewModel.clearTool(); showTool = null }
      ToolPage(tool = tool, viewModel = viewModel, onMenu = { scope.launch { drawerState.open() } })
      return@ModalNavigationDrawer
    }
    // A flowing accent "aurora" light tied to the theme: it greets the empty screen from the bottom,
    // then — once chatting — rides in from the top only while the model generates a reply, so it never
    // collides with the bottom input fade (AGSL shader, with a radial fallback).
    val surfaceColor = MaterialTheme.colorScheme.surface
    // Both aurora tones are the selected accent (a bright shade + a lighter one of the SAME hue), so
    // the light clearly reads as the chosen color rather than mixing in a contrasting secondary.
    val accent1 = MaterialTheme.colorScheme.primary
    // Keep the second tone saturated (only a slight lift) so the light stays vibrant, not washed out.
    val accent2 = androidx.compose.ui.graphics.lerp(accent1, Color.White, 0.18f)
    val hasMessages = state.messages.isNotEmpty()
    // Top glow follows generation; bottom glow is the welcome-screen resting light. Both fade smoothly.
    val topIntensity by
      animateFloatAsState(
        targetValue = if (state.streaming) 1f else 0f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "auroraTop",
      )
    val bottomIntensity by
      animateFloatAsState(
        targetValue = if (hasMessages) 0f else 1f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "auroraBottom",
      )
    Scaffold(
      containerColor = Color.Transparent,
      modifier =
        Modifier.fillMaxSize()
          .accentAuroraLight(surfaceColor, accent1, accent2, bottomIntensity, topIntensity),
      topBar = {
        TopAppBar(
          colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
          navigationIcon = {
            // Morphs hamburger↔arrow as the drawer opens/closes (Google DrawerToggle behaviour).
            AnimatedNavIcon(isBack = drawerState.isOpen) {
              scope.launch { if (drawerState.isOpen) drawerState.close() else drawerState.open() }
            }
          },
          title = {
            // Before the first message the title is a model picker; once chatting the model is locked
            // for that conversation, so it becomes a plain, non-interactive title.
            if (hasMessages) {
              Column {
                Text("Rin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (state.modelName.isNotEmpty()) {
                  Text(
                    state.modelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            } else {
              var menuOpen by remember { mutableStateOf(false) }
              val downloaded = modelManagerViewModel.getAllDownloadedModels()
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { menuOpen = true }.padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Column {
                  Text("Rin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                  if (state.modelName.isNotEmpty()) {
                    Text(
                      state.modelName,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
                Icon(
                  Icons.Rounded.ArrowDropDown,
                  contentDescription = tr("model.choose_cd", "Choose model"),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (menuOpen) {
                ModelPickerSheet(
                  downloaded = downloaded,
                  state = state,
                  displayNameFor = viewModel::displayNameFor,
                  onDismiss = { menuOpen = false },
                  onSelectLocal = {
                    menuOpen = false
                    viewModel.setActiveModel(it)
                  },
                  onSelectApi = {
                    menuOpen = false
                    viewModel.setActiveApiModel(it)
                  },
                  onUnload = {
                    menuOpen = false
                    viewModel.unloadModel()
                  },
                  onManage = {
                    menuOpen = false
                    openSettings("llm")
                  },
                )
              }
            }
          },
          actions = {
            // Ghost mode is a "before you start" choice; once chatting it's hidden (an inline banner
            // above the input keeps it visible when on).
            if (!hasMessages) {
              IconButton(onClick = { viewModel.setGhostMode(!state.ghostMode) }) {
                Icon(
                  Icons.Rounded.VisibilityOff,
                  contentDescription = tr("chat.ghost_mode_cd", "Ghost mode"),
                  tint =
                    if (state.ghostMode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          },
        )
      },
    ) { padding ->
      val topInset = padding.calculateTopPadding()
      Box(modifier = Modifier.fillMaxSize().imePadding()) {
        // Fade the list's top edge so bubbles dissolve under the transparent top bar (revealing the
        // light behind it) instead of being hard-cut or hidden by an opaque white strip.
        Box(
          modifier =
            Modifier.fillMaxSize()
              .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
              .drawWithContent {
                drawContent()
                // Keep content fully clear behind the entire top bar so the header reads cleanly over
                // the background/aurora (never over scrolled text), then a short soft dissolve just
                // below it — no wide gradient band.
                val clearPx = topInset.toPx()
                val fadePx = 20.dp.toPx()
                val start = (clearPx / size.height).coerceIn(0f, 0.999f)
                val end = ((clearPx + fadePx) / size.height).coerceIn(start + 0.001f, 1f)
                drawRect(
                  brush =
                    Brush.verticalGradient(
                      0f to Color.Transparent,
                      start to Color.Transparent,
                      end to Color.Black,
                      1f to Color.Black,
                    ),
                  blendMode = BlendMode.DstIn,
                )
              }
        ) {
        when {
          state.messages.isNotEmpty() || state.streaming ->
            MessageList(
              messages = state.messages,
              streaming = state.streaming,
              searchingWeb = state.searchingWeb,
              streamingText = state.streamingText,
              streamingThinking = state.streamingThinking,
              onRetry = viewModel::regenerate,
              onSpeak = if (state.canSpeak) { text -> viewModel.speak(text) } else null,
              editingTs = editingTs,
              selectedTs = selectedTs,
              onSelect = { selectedTs = if (selectedTs == it.ts) null else it.ts },
              onStartEdit = {
                selectedTs = null
                editingTs = it.ts
              },
              onCancelEdit = { editingTs = null },
              onSubmitEdit = { msg, text ->
                editingTs = null
                viewModel.editAndResend(msg, text)
              },
              contentPadding =
                PaddingValues(
                  start = 16.dp,
                  end = 16.dp,
                  top = topInset + 8.dp,
                  bottom = with(density) { inputHeightPx.toDp() } + 8.dp,
                ),
            )
          state.phase == ChatPhase.NO_MODEL -> NoModel(state.errorMessage) { openSettings("llm") }
          state.phase == ChatPhase.ERROR ->
            ModelError(state.modelName, state.errorMessage) { openSettings("llm") }
          else -> EmptyState(state.phase)
        }
        }

        // Bottom: once there are messages, a fade to surface + opaque input so bubbles dissolve
        // under it. On the empty/first screen we keep it transparent so the resting blue glow
        // isn't cut off by the input area.
        Column(
          modifier =
            Modifier.align(Alignment.BottomCenter)
              .fillMaxWidth()
              .onSizeChanged { inputHeightPx = it.height }
              .then(
                if (hasMessages)
                  Modifier.drawBehind {
                    // Content stays visible until ~the middle of the input bubble, then the surface
                    // takes over so nothing shows below the bubble. A long, gentle fade — no hard
                    // cutoff band. (Measured from the top of this column, which includes the nav-bar
                    // inset since drawBehind sits above navigationBarsPadding in the chain.)
                    val fadeStart = 48.dp.toPx()
                    val fadeEnd = 96.dp.toPx()
                    val s = (fadeStart / size.height).coerceIn(0f, 0.999f)
                    val e = (fadeEnd / size.height).coerceIn(s + 0.001f, 1f)
                    drawRect(
                      brush =
                        Brush.verticalGradient(
                          0f to Color.Transparent,
                          s to Color.Transparent,
                          e to surfaceColor,
                          1f to surfaceColor,
                        )
                    )
                  }
                else Modifier
              )
              .navigationBarsPadding(),
        ) {
          if (state.ghostMode) {
            Text(
              tr("chat.ghost_banner", "Ghost mode — this chat won't be saved"),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
          }
          InputBar(
              input = input,
              onInputChange = { input = it },
              enabled = state.phase == ChatPhase.READY && !state.streaming,
              streaming = state.streaming,
              supportsImage = state.supportsImage,
              supportsAudio = state.supportsAudio,
              listening = state.listening,
              partialTranscript = state.partialTranscript,
              voiceLevel = state.voiceLevel,
              sttEnabled = state.sttEnabled,
              onVoiceStart = viewModel::startVoiceInput,
              onVoiceStop = viewModel::stopVoiceInput,
              onSend = viewModel::send,
              onStop = viewModel::stopGeneration,
            )
        }
      }
    }
  }

  pendingDeleteId?.let { id ->
    AlertDialog(
      onDismissRequest = { pendingDeleteId = null },
      title = { Text(tr("chat.delete_title", "Delete chat?")) },
      text = { Text(tr("chat.delete_message", "This conversation will be permanently deleted.")) },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.deleteConversation(id)
            pendingDeleteId = null
          }
        ) {
          Text(tr("common.delete", "Delete"))
        }
      },
      dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text(tr("common.cancel", "Cancel")) } },
    )
  }

  pendingRename?.let { conv ->
    var title by remember(conv.id) { mutableStateOf(conv.title) }
    AlertDialog(
      onDismissRequest = { pendingRename = null },
      title = { Text(tr("chat.rename", "Rename chat")) },
      text = {
        OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
      },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.renameConversation(conv.id, title)
            pendingRename = null
          }
        ) {
          Text(tr("common.save", "Save"))
        }
      },
      dismissButton = { TextButton(onClick = { pendingRename = null }) { Text(tr("common.cancel", "Cancel")) } },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatDrawer(
  state: ChatUiState,
  activeMode: String,
  onNewChat: () -> Unit,
  onOpenConversation: (Conversation) -> Unit,
  onDeleteConversation: (String) -> Unit,
  onRenameConversation: (Conversation) -> Unit,
  onOpenTool: (String) -> Unit,
  onOpenSettings: () -> Unit,
) {
  ModalDrawerSheet {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
      Text(
        "Rin",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 12.dp),
      )
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DrawerNavRow(Icons.Rounded.Edit, tr("chat.new_chat", "New chat"), selected = activeMode == "chat", onClick = onNewChat)
        DrawerNavRow(Icons.Rounded.GraphicEq, tr("tool.transcribe", "Transcribe audio"), selected = activeMode == "transcribe") { onOpenTool("transcribe") }
        DrawerNavRow(Icons.Rounded.Translate, tr("tool.translate", "Translate"), selected = activeMode == "translate") { onOpenTool("translate") }
      }

      Spacer(Modifier.height(16.dp))
      Text(
        tr("chat.recents", "Recents"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
      )
      LazyColumn(
        modifier =
          Modifier.weight(1f)
            // Soft dissolve at the list's bottom edge so the recents don't look hard-cut above the
            // user/settings footer when the list scrolls past it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
              drawContent()
              val fadePx = 24.dp.toPx()
              val start = ((size.height - fadePx) / size.height).coerceIn(0f, 0.999f)
              drawRect(
                brush =
                  Brush.verticalGradient(
                    0f to Color.Black,
                    start to Color.Black,
                    1f to Color.Transparent,
                  ),
                blendMode = BlendMode.DstIn,
              )
            }
      ) {
        items(state.conversations, key = { it.id }) { conv ->
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
              Modifier.fillMaxWidth()
                .combinedClickable(onClick = { onOpenConversation(conv) }, onLongClick = { onRenameConversation(conv) })
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
          ) {
            Icon(
              Icons.Rounded.ChatBubbleOutline,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Text(
              conv.title,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 1,
              modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onDeleteConversation(conv.id) }) {
              Icon(
                Icons.Rounded.Delete,
                contentDescription = tr("common.delete", "Delete"),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
      ) {
        val name = state.username.ifBlank { tr("chat.you", "You") }
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier.size(36.dp).background(gemmaBrush(), CircleShape),
        ) {
          Text(
            name.first().uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
          )
        }
        Spacer(Modifier.size(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onOpenSettings) {
          Icon(Icons.Rounded.Settings, contentDescription = tr("settings.title", "Settings"))
        }
      }
    }
  }
}

/** A drawer destination styled as a pill — same height/gaps for all; the active one is filled. */
@Composable
private fun DrawerNavRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(28.dp),
    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
    contentColor =
      if (selected) MaterialTheme.colorScheme.onSecondaryContainer
      else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
      Text(label, style = MaterialTheme.typography.titleSmall)
    }
  }
}
