// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kaiwa.data.BuiltInTaskId
import app.kaiwa.data.Model
import app.kaiwa.data.ModelDownloadStatusType
import app.kaiwa.i18n.APP_LANGUAGES
import app.kaiwa.i18n.appLanguageNative
import app.kaiwa.i18n.tr
import app.kaiwa.proto.ImportedModel
import app.kaiwa.speech.TTS_VOICES
import app.kaiwa.ui.common.ConfigEditorsPanel
import app.kaiwa.ui.modelmanager.ModelManagerViewModel
import app.kaiwa.ui.theme.APP_BACKGROUNDS
import app.kaiwa.ui.theme.APP_THEMES
import app.kaiwa.ui.theme.appBackgroundLabelDefault
import app.kaiwa.ui.theme.appThemeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSettings(
  viewModel: MainChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  state: ChatUiState,
  onBack: () -> Unit,
  initialPage: String = "main",
) {
  // Page back-stack seeded with the ancestor chain so backing out of a deep page (e.g. opened
  // straight to Language models from the picker) walks up one level at a time instead of jumping out.
  val stack = remember {
    mutableStateListOf<String>().apply {
      add("main")
      if (initialPage != "main") add(initialPage)
    }
  }
  val page = stack.last()
  fun go(p: String) = stack.add(p)
  fun back() {
    if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack()
  }
  BackHandler { back() }
  val title =
    when (page) {
      "prompt" -> tr("settings.system_prompt", "System prompt")
      "providers" -> tr("settings.providers", "Providers")
      "search" -> tr("settings.web_search", "Web search")
      "mcp" -> tr("settings.mcp_servers", "MCP servers")
      "skills" -> tr("settings.skills", "Skills")
      "docs" -> tr("settings.memory", "Memory")
      "addons" -> tr("settings.on_device_addons", "On-device add-ons")
      "llm" -> tr("models.language_models", "Download local models")
      "manager" -> tr("settings.model_manager", "Model manager")
      "prefs" -> tr("settings.preferences", "Preferences")
      else -> tr("settings.title", "Settings")
    }
  Scaffold(
    // Subtle gray page so the grouped white cards read as distinct sections (Gemini style).
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = { AnimatedNavIcon(isBack = true) { back() } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      )
    }
  ) { padding ->
    AnimatedContent(
      targetState = page,
      modifier = Modifier.padding(padding),
      transitionSpec = {
        val forward = pageDepth(targetState) >= pageDepth(initialState)
        val w = if (forward) 1 else -1
        (slideInHorizontally(tween(240)) { w * it / 4 } + fadeIn(tween(240))) togetherWith
          (slideOutHorizontally(tween(240)) { -w * it / 4 } + fadeOut(tween(240)))
      },
      label = "settingsPage",
    ) { p ->
      when (p) {
        "prompt" -> SystemPromptPage(state, viewModel::setSystemPrompt)
        "providers" -> ProvidersPage(state, viewModel)
        "search" -> WebSearchPage(state, viewModel)
        "mcp" -> McpServersPage(state, viewModel)
        "skills" -> SkillsPage(state, viewModel)
        "docs" -> DocumentsPage(state, viewModel)
        "addons" -> OnDeviceAddonsPage(state, viewModel, modelManagerViewModel)
        "llm" -> LlmModelsPage(modelManagerViewModel)
        "manager" -> ModelManagerPage(state, viewModel, modelManagerViewModel)
        "prefs" -> PreferencesPage(state, viewModel) { go(it) }
        else -> SettingsMain(state, viewModel) { go(it) }
      }
    }
  }
}

/** Relative nesting depth, so page transitions slide in the right direction. */
private fun pageDepth(page: String): Int =
  when (page) {
    "main" -> 0
    else -> 1
  }

/** Gray section label above a card. */
@Composable
private fun SectionLabel(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 6.dp),
  )
}

/** A rounded white card grouping a set of setting rows. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
  Surface(
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surface,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(content = content)
  }
}

/** Hairline divider between rows in a card, indented to clear the leading icon. */
@Composable
private fun RowDivider() {
  HorizontalDivider(
    modifier = Modifier.padding(start = 54.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
  )
}

@Composable
private fun NavItem(
  icon: ImageVector,
  title: String,
  subtitle: String?,
  chevron: Boolean = true,
  tint: Color? = null,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.size(16.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall, color = tint ?: MaterialTheme.colorScheme.onSurface)
      if (subtitle != null) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    if (chevron) {
      Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun ToggleItem(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.size(16.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.size(8.dp))
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

/** Gemini-style header: avatar + greeting + a button into personal preferences. */
@Composable
private fun SettingsHeader(name: String, onOpenPreferences: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPreferences).padding(vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(72.dp).clip(CircleShape).background(gemmaBrush()),
    ) {
      Text(
        (name.firstOrNull() ?: 'Y').uppercase(),
        color = Color.White,
        style = MaterialTheme.typography.headlineMedium,
      )
    }
    Text(
      tr("settings.hello", "Hello") + (if (name.isNotBlank()) ", $name" else "") + "!",
      style = MaterialTheme.typography.headlineSmall,
    )
    OutlinedButton(onClick = onOpenPreferences) { Text(tr("settings.preferences", "Preferences")) }
  }
}

@Composable
private fun SettingsMain(
  state: ChatUiState,
  viewModel: MainChatViewModel,
  openPage: (String) -> Unit,
) {
  var showClearConfirm by remember { mutableStateOf(false) }

  if (showClearConfirm) {
    AlertDialog(
      onDismissRequest = { showClearConfirm = false },
      title = { Text(tr("settings.delete_all_title", "Delete all chats?")) },
      text = { Text(tr("settings.delete_all_message", "All saved conversations will be permanently deleted.")) },
      confirmButton = {
        TextButton(onClick = { viewModel.clearAllData(); showClearConfirm = false }) { Text(tr("settings.delete_all_confirm", "Delete all")) }
      },
      dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text(tr("common.cancel", "Cancel")) } },
    )
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    SettingsHeader(state.username) { openPage("prefs") }

    SectionLabel(tr("settings.sec_models", "Models"))
    SettingsCard {
      NavItem(Icons.Rounded.Download, tr("settings.download_local_models", "Download local models"), tr("settings.download_local_models_desc", "On-device chat models — download or import")) { openPage("llm") }
      RowDivider()
      NavItem(Icons.Rounded.Extension, tr("settings.on_device_addons", "On-device add-ons"), tr("settings.on_device_addons_desc", "Voices, speech-to-text and memory — and free up space")) { openPage("addons") }
      RowDivider()
      NavItem(Icons.Rounded.Tune, tr("settings.model_manager", "Model manager"), tr("settings.model_manager_desc", "Configure every model and pick your defaults")) { openPage("manager") }
    }

    SectionLabel(tr("settings.sec_connections", "Connections"))
    SettingsCard {
      NavItem(Icons.Rounded.Cloud, tr("settings.providers", "Providers"), tr("settings.providers_desc2", "API keys, endpoints and which models are on")) { openPage("providers") }
      RowDivider()
      NavItem(Icons.Rounded.Search, tr("settings.web_search", "Web search"), tr("settings.web_search_desc", "DuckDuckGo, SearXNG or Ollama")) { openPage("search") }
      RowDivider()
      NavItem(Icons.Rounded.Hub, tr("settings.mcp_servers", "MCP servers"), tr("settings.mcp_servers_desc", "Connect external tool servers the model can call")) { openPage("mcp") }
      RowDivider()
      NavItem(Icons.Rounded.AutoAwesome, tr("settings.skills", "Skills"), tr("settings.skills_desc", "Instruction packs the model can load on demand")) { openPage("skills") }
      RowDivider()
      NavItem(Icons.Rounded.Memory, tr("settings.memory", "Memory"), tr("settings.memory_desc", "Notes & documents Rin can recall")) { openPage("docs") }
    }

    SectionLabel(tr("settings.sec_privacy", "Privacy & data"))
    SettingsCard {
      NavItem(Icons.Rounded.Delete, tr("settings.delete_all_history", "Delete all chat history"), null, chevron = false, tint = MaterialTheme.colorScheme.error) { showClearConfirm = true }
    }

    Column(
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(tr("settings.about_app", "Rin"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(tr("settings.about_author", "by Loke-60000"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** Personal settings: name, how Gemma behaves, and per-user toggles. */
@Composable
private fun PreferencesPage(state: ChatUiState, viewModel: MainChatViewModel, openPage: (String) -> Unit) {
  var name by remember(state.username) { mutableStateOf(state.username) }
  var showLang by remember { mutableStateOf(false) }
  var showTheme by remember { mutableStateOf(false) }
  var showBg by remember { mutableStateOf(false) }

  if (showBg) {
    AlertDialog(
      onDismissRequest = { showBg = false },
      title = { Text(tr("settings.background", "Background")) },
      text = {
        Column {
          APP_BACKGROUNDS.forEach { b ->
            Row(
              modifier = Modifier.fillMaxWidth().clickable { viewModel.setBackgroundMode(b.id); showBg = false }.padding(vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier =
                  Modifier.size(22.dp).clip(CircleShape).background(b.paper)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
              )
              Spacer(Modifier.size(14.dp))
              Text(tr(b.labelKey, b.labelDefault), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
              androidx.compose.material3.RadioButton(
                selected = state.backgroundMode == b.id,
                onClick = { viewModel.setBackgroundMode(b.id); showBg = false },
              )
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { showBg = false }) { Text(tr("common.cancel", "Cancel")) } },
    )
  }

  if (showTheme) {
    AlertDialog(
      onDismissRequest = { showTheme = false },
      title = { Text(tr("settings.theme", "Theme")) },
      text = {
        Column {
          APP_THEMES.forEach { t ->
            Row(
              modifier = Modifier.fillMaxWidth().clickable { viewModel.setTheme(t.id); showTheme = false }.padding(vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(t.accent))
              Spacer(Modifier.size(14.dp))
              Text(t.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
              androidx.compose.material3.RadioButton(
                selected = state.theme == t.id,
                onClick = { viewModel.setTheme(t.id); showTheme = false },
              )
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { showTheme = false }) { Text(tr("common.cancel", "Cancel")) } },
    )
  }

  if (showLang) {
    AlertDialog(
      onDismissRequest = { showLang = false },
      title = { Text(tr("settings.language", "Language")) },
      text = {
        Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
          DefaultRadioRow(tr("settings.language_system", "System default"), null, state.appLanguage == null) {
            viewModel.setAppLanguage(null)
            showLang = false
          }
          APP_LANGUAGES.forEach { lang ->
            DefaultRadioRow(lang.native, lang.english, state.appLanguage == lang.code) {
              viewModel.setAppLanguage(lang.code)
              showLang = false
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { showLang = false }) { Text(tr("common.cancel", "Cancel")) } },
    )
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    SettingsCard {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("settings.your_name", "Your name"), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(tr("settings.your_name_hint", "How Rin addresses you")) },
          )
          androidx.compose.material3.Button(
            onClick = { viewModel.setUsername(name) },
            enabled = name.trim() != state.username,
          ) {
            Text(tr("common.save", "Save"))
          }
        }
      }
    }

    SettingsCard {
      NavItem(Icons.Rounded.Palette, tr("settings.theme", "Theme"), appThemeLabel(state.theme)) { showTheme = true }
      RowDivider()
      NavItem(
        Icons.Rounded.Contrast,
        tr("settings.background", "Background"),
        tr(APP_BACKGROUNDS.firstOrNull { it.id == state.backgroundMode }?.labelKey ?: "bg.white", appBackgroundLabelDefault(state.backgroundMode)),
      ) {
        showBg = true
      }
      RowDivider()
      NavItem(
        Icons.Rounded.Language,
        tr("settings.language", "Language"),
        appLanguageNative(state.appLanguage) ?: tr("settings.language_system", "System default"),
      ) {
        showLang = true
      }
      RowDivider()
      NavItem(Icons.Rounded.Description, tr("settings.system_prompt", "System prompt"), tr("settings.system_prompt_desc", "Rewrite how Rin behaves")) { openPage("prompt") }
      RowDivider()
      ToggleItem(Icons.Rounded.Mic, tr("settings.voice_typing", "Voice typing"), tr("settings.voice_typing_short", "On-device speech-to-text (~40 MB)"), state.sttEnabled, viewModel::setSttEnabled)
      RowDivider()
      ToggleItem(Icons.Rounded.VisibilityOff, tr("settings.ghost_mode", "Ghost mode"), tr("settings.ghost_mode_desc", "Don't save these chats"), state.ghostMode, viewModel::setGhostMode)
      RowDivider()
      val backTapCtx = LocalContext.current
      ToggleItem(
        Icons.Rounded.TouchApp,
        tr("settings.back_tap", "Double-tap back"),
        tr("settings.back_tap_desc", "Tap the back of the phone twice to open the assistant"),
        state.backTapEnabled,
      ) { enabled ->
        // The gesture opens the assistant from anywhere, which needs the "display over other apps"
        // grant. Send the user to grant it when first enabling.
        if (enabled && !android.provider.Settings.canDrawOverlays(backTapCtx)) {
          backTapCtx.startActivity(
            android.content.Intent(
              android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
              android.net.Uri.parse("package:${backTapCtx.packageName}"),
            )
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
          )
        }
        viewModel.setBackTapEnabled(enabled)
      }
      if (state.backTapEnabled) {
        RowDivider()
        var sensitivity by
          remember(state.backTapSensitivity) {
            mutableFloatStateOf(state.backTapSensitivity.toFloat())
          }
        Column(modifier = Modifier.padding(start = 56.dp, end = 20.dp, top = 6.dp, bottom = 14.dp)) {
          Text(
            tr("settings.back_tap_sensitivity", "Tap sensitivity"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            tr(
              "settings.back_tap_sensitivity_desc",
              "Lower it if the assistant opens by accident; raise it if a double-tap is missed.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Slider(
            value = sensitivity,
            onValueChange = { sensitivity = it },
            onValueChangeFinished = { viewModel.setBackTapSensitivity(sensitivity.toInt()) },
            valueRange = 0f..100f,
            steps = 9,
          )
        }
      }
      RowDivider()
      ToggleItem(
        Icons.Rounded.ScreenshotMonitor,
        tr("settings.screen_reading", "See your screen"),
        tr("settings.screen_reading_desc", "Adds a 'See screen' button to the assistant — it grabs one screenshot only when you tap it"),
        state.screenReadingEnabled,
        viewModel::setScreenReadingEnabled,
      )
    }
  }
}

@Composable
private fun SystemPromptPage(state: ChatUiState, onSave: (String) -> Unit) {
  var prompt by remember { mutableStateOf(state.systemPrompt) }
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(tr("prompt.desc", "Customize how Rin behaves. Saved for all chats."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(), minLines = 8)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      androidx.compose.material3.Button(onClick = { onSave(prompt) }) { Text(tr("common.save", "Save")) }
      OutlinedButton(onClick = { prompt = ChatPrefs.DEFAULT_SYSTEM_PROMPT }) { Text(tr("prompt.reset_default", "Reset to default")) }
    }
  }
}

@Composable
private fun WebSearchPage(state: ChatUiState, viewModel: MainChatViewModel) {
  var searxng by remember { mutableStateOf(state.searxngUrl) }
  var ollamaKey by remember { mutableStateOf(state.ollamaSearchKey) }
  val hasOllamaProvider =
    state.apiCredentials.any {
      it.provider == ApiProvider.OLLAMA || it.provider == ApiProvider.OLLAMA_CLOUD
    }
  val options = listOf("NONE" to tr("search.off", "Off"), "DUCKDUCKGO" to "DuckDuckGo", "SEARXNG" to "SearXNG", "OLLAMA" to "Ollama")
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(tr("search.desc", "When enabled, Rin searches the web before answering."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    options.forEach { (value, label) ->
      Row(
        modifier = Modifier.fillMaxWidth().clickable { viewModel.setWebSearchProvider(value) },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        androidx.compose.material3.RadioButton(selected = state.webSearchProvider == value, onClick = { viewModel.setWebSearchProvider(value) })
        Text(label)
      }
    }
    Spacer(Modifier.size(8.dp))
    // Only show the field the selected provider needs.
    when (state.webSearchProvider) {
      "SEARXNG" -> {
        OutlinedTextField(searxng, { searxng = it }, label = { Text(tr("search.searxng_url", "SearXNG URL")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.Button(onClick = { viewModel.setSearxngUrl(searxng) }) { Text(tr("common.save", "Save")) }
      }
      "OLLAMA" -> {
        if (hasOllamaProvider && ollamaKey.isBlank()) {
          Text(
            tr("search.ollama_reusing", "Reusing your saved Ollama provider key — no need to enter it again."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        } else {
          OutlinedTextField(
            ollamaKey,
            { ollamaKey = it },
            label = { Text(if (hasOllamaProvider) tr("search.ollama_override", "Override Ollama key (optional)") else tr("search.ollama_key", "Ollama search API key")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          androidx.compose.material3.Button(onClick = { viewModel.setOllamaSearchKey(ollamaKey) }) { Text(tr("common.save", "Save")) }
          if (!hasOllamaProvider) {
            Text(
              tr("search.ollama_tip", "Tip: add an Ollama provider under Providers and this fills in automatically."),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

private fun fmtBytes(bytes: Long): String =
  when {
    bytes <= 0L -> ""
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
    else -> "%.0f KB".format(bytes / 1e3)
  }

/**
 * On-device support models in one place: voices, speech-to-text, the memory embedder, plus a
 * purge-all action. The language model itself lives on its own "Download local models" page.
 */
@Composable
private fun OnDeviceAddonsPage(
  state: ChatUiState,
  viewModel: MainChatViewModel,
  mmVm: ModelManagerViewModel,
) {
  val mm by mmVm.uiState.collectAsState()
  val llmDownloaded = remember(mm.modelDownloadStatus) { mmVm.getAllDownloadedModels() }
  val anyDownloaded =
    llmDownloaded.isNotEmpty() ||
      state.installedVoices.isNotEmpty() ||
      state.sttDownloaded ||
      state.embedderDownloaded
  var showPurge by remember { mutableStateOf(false) }

  if (showPurge) {
    AlertDialog(
      onDismissRequest = { showPurge = false },
      title = { Text(tr("models.purge_title", "Purge all models?")) },
      text = {
        Text(
          tr(
            "models.purge_message",
            "Every downloaded model — language models, voices, speech-to-text and the memory embedder — will be deleted to free space. You can re-download them later.",
          )
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            llmDownloaded.forEach { mmVm.deleteModel(it) }
            viewModel.purgeSpeechAndSupportModels()
            showPurge = false
          }
        ) {
          Text(tr("models.purge_confirm", "Purge all"))
        }
      },
      dismissButton = { TextButton(onClick = { showPurge = false }) { Text(tr("common.cancel", "Cancel")) } },
    )
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Text(
      tr(
        "addons.intro",
        "These add-ons run entirely on your device — no cloud, no account. Install what you need and delete anything to free up space.",
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (viewModel.ttsEngineSupported()) {
      SectionLabel(tr("models.voices", "Voices"))
      SettingsCard { VoicesSection(state, viewModel) }
    }

    SectionLabel(tr("models.speech_to_text", "Speech-to-text"))
    SettingsCard {
      ModelStorageRow(
        icon = Icons.Rounded.Mic,
        title = tr("models.speech_to_text", "Speech-to-text"),
        subtitle = tr("models.speech_to_text_desc", "Turns speech into text on-device — used by the mic when Voice typing is on. ~40 MB."),
        present = state.sttDownloaded,
        installing = state.sttInstalling,
        size = state.sttSize,
        onInstall = { viewModel.installSttModel() },
        onDelete = { viewModel.deleteSttModel() },
      )
      RowDivider()
      ToggleItem(
        Icons.Rounded.Mic,
        tr("settings.voice_typing", "Voice typing"),
        tr("settings.voice_typing_short", "On-device speech-to-text (~40 MB)"),
        state.sttEnabled,
        viewModel::setSttEnabled,
      )
    }

    SectionLabel(tr("models.memory_embedder", "Memory embedder"))
    SettingsCard {
      ModelStorageRow(
        icon = Icons.Rounded.Description,
        title = tr("models.memory_embedder", "Memory embedder"),
        subtitle = tr("models.memory_embedder_desc", "Finds relevant parts of your saved documents — used when Memory is on. ~55 MB."),
        present = state.embedderDownloaded,
        installing = state.embedderInstalling,
        size = state.embedderSize,
        onInstall = { viewModel.installEmbedderModel() },
        onDelete = { viewModel.deleteEmbedderModel() },
      )
    }

    OutlinedButton(
      onClick = { showPurge = true },
      enabled = anyDownloaded,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.size(8.dp))
      Text(tr("models.purge_all", "Purge all downloaded models"))
    }
  }
}

/**
 * The one place every usable model lives: on-device models and each enabled cloud model, grouped by
 * source. Each row shows the default roles it holds and opens a config editor; defaults (chat / popup
 * / titles) are set from inside that editor. This absorbs the old standalone "Default models" page.
 */
@Composable
private fun ModelManagerPage(state: ChatUiState, viewModel: MainChatViewModel, mmVm: ModelManagerViewModel) {
  val mm by mmVm.uiState.collectAsState()
  val localModels = remember(mm.modelDownloadStatus) { mmVm.getAllDownloadedModels() }
  var localEditing by remember { mutableStateOf<Model?>(null) }
  var remoteEditing by remember { mutableStateOf<ApiModelConfig?>(null) }

  localEditing?.let { model ->
    LocalModelEditorSheet(model, state, viewModel, mmVm, onClose = { localEditing = null })
    return
  }
  remoteEditing?.let { cfg ->
    RemoteModelEditorSheet(cfg, state, viewModel, onClose = { remoteEditing = null })
    return
  }

  val cloudByProvider = state.apiModels.groupBy { it.provider }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Text(
      tr("manager.intro", "Pick which model handles each job, then tap any model below to tune it."),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (localModels.isNotEmpty() || state.apiModels.isNotEmpty()) {
      DefaultsSection(state, viewModel, localModels)
    }

    if (localModels.isEmpty() && state.apiModels.isEmpty()) {
      SettingsCard {
        Text(
          tr("manager.empty", "No models yet — download one or add a provider."),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(16.dp),
        )
      }
    }

    if (localModels.isNotEmpty()) {
      SectionLabel(tr("manager.on_device", "On-device"))
      SettingsCard {
        localModels.forEachIndexed { i, m ->
          if (i > 0) RowDivider()
          val label = viewModel.displayNameFor(m.name, m.displayName.ifEmpty { m.name })
          ModelRow(
            title = label,
            subtitle = tr("model.on_device", "On-device"),
            isChat = !state.isApiModel && state.modelName == label,
            isPopup = state.popupModel == "local:${m.name}",
            isTitles = state.titleModel == "local:${m.name}",
            onClick = { localEditing = m },
          )
        }
      }
    }

    cloudByProvider.forEach { (provider, models) ->
      SectionLabel(tr("manager.cloud", "Cloud · %s").format(provider.label))
      SettingsCard {
        models.forEachIndexed { i, cfg ->
          if (i > 0) RowDivider()
          ModelRow(
            title = cfg.displayName,
            subtitle = cfg.modelId,
            isChat = state.isApiModel && state.modelName == cfg.displayName,
            isPopup = state.popupModel == "api:${cfg.id}",
            isTitles = state.titleModel == "api:${cfg.id}",
            onClick = { remoteEditing = cfg },
          )
        }
      }
    }
  }
}

private data class DefaultChoice(
  val label: String,
  val sublabel: String?,
  val selected: Boolean,
  val onSelect: () -> Unit,
)

/** Per-job default pickers — which model is used for chat, the assistant popup, and chat titles. */
@Composable
private fun DefaultsSection(
  state: ChatUiState,
  viewModel: MainChatViewModel,
  localModels: List<Model>,
) {
  // role is one of "chat" / "popup" / "titles"; non-null while its picker dialog is open.
  var picking by remember { mutableStateOf<String?>(null) }
  val sameAsChat = tr("defaults.same_as_chat", "Same as chat")

  fun nameForTag(tag: String?): String =
    when {
      tag == null -> sameAsChat
      tag.startsWith("local:") -> {
        val name = tag.removePrefix("local:")
        viewModel.displayNameFor(name, name)
      }
      tag.startsWith("api:") -> state.apiModels.find { "api:${it.id}" == tag }?.displayName ?: tag
      else -> tag
    }

  SectionLabel(tr("defaults.section", "Defaults"))
  SettingsCard {
    DefaultPickRow(
      Icons.Rounded.Forum,
      tr("defaults.chat", "Chat model"),
      state.modelName.ifEmpty { tr("defaults.none", "Not set") },
    ) {
      picking = "chat"
    }
    RowDivider()
    DefaultPickRow(
      Icons.Rounded.Bolt,
      tr("defaults.popup", "Assistant popup"),
      nameForTag(state.popupModel),
    ) {
      picking = "popup"
    }
    RowDivider()
    DefaultPickRow(
      Icons.Rounded.Title,
      tr("defaults.titles", "Chat titles"),
      nameForTag(state.titleModel),
    ) {
      picking = "titles"
    }
  }

  picking?.let { role ->
    val choices = buildList {
      if (role != "chat") {
        val tag = if (role == "popup") state.popupModel else state.titleModel
        add(
          DefaultChoice(tr("defaults.same_as_chat", "Same as chat"), null, tag == null) {
            if (role == "popup") viewModel.setPopupModel(null) else viewModel.setTitleModel(null)
          }
        )
      }
      localModels.forEach { m ->
        val label = viewModel.displayNameFor(m.name, m.displayName.ifEmpty { m.name })
        val selected =
          when (role) {
            "chat" -> !state.isApiModel && state.modelName == label
            "popup" -> state.popupModel == "local:${m.name}"
            else -> state.titleModel == "local:${m.name}"
          }
        add(DefaultChoice(label, tr("model.on_device", "On-device"), selected) {
          when (role) {
            "chat" -> viewModel.setActiveModel(m)
            "popup" -> viewModel.setPopupModel("local:${m.name}")
            else -> viewModel.setTitleModel("local:${m.name}")
          }
        })
      }
      state.apiModels.forEach { cfg ->
        val selected =
          when (role) {
            "chat" -> state.isApiModel && state.modelName == cfg.displayName
            "popup" -> state.popupModel == "api:${cfg.id}"
            else -> state.titleModel == "api:${cfg.id}"
          }
        add(DefaultChoice(cfg.displayName, cfg.provider.label, selected) {
          when (role) {
            "chat" -> viewModel.setActiveApiModel(cfg)
            "popup" -> viewModel.setPopupModel("api:${cfg.id}")
            else -> viewModel.setTitleModel("api:${cfg.id}")
          }
        })
      }
    }
    val title =
      when (role) {
        "chat" -> tr("defaults.chat", "Chat model")
        "popup" -> tr("defaults.popup", "Assistant popup")
        else -> tr("defaults.titles", "Chat titles")
      }
    ModelChoiceDialog(title, choices) { picking = null }
  }
}

@Composable
private fun DefaultPickRow(icon: ImageVector, label: String, current: String, onClick: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.titleSmall)
      Text(current, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun ModelChoiceDialog(title: String, choices: List<DefaultChoice>, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        choices.forEach { choice ->
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clickable {
                  choice.onSelect()
                  onDismiss()
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = choice.selected,
              onClick = {
                choice.onSelect()
                onDismiss()
              },
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
              Text(choice.label, style = MaterialTheme.typography.bodyLarge)
              choice.sublabel?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(tr("common.cancel", "Cancel")) } },
  )
}

/** A model row in the manager: name, source, plus small badges for the default roles it holds. */
@Composable
private fun ModelRow(
  title: String,
  subtitle: String,
  isChat: Boolean,
  isPopup: Boolean,
  isTitles: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (isChat || isPopup || isTitles) {
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (isChat) RoleBadge(tr("manager.role_chat", "Chat"))
          if (isPopup) RoleBadge(tr("manager.role_popup", "Popup"))
          if (isTitles) RoleBadge(tr("manager.role_titles", "Titles"))
        }
      }
    }
    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun RoleBadge(text: String) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.primaryContainer,
  ) {
    Text(
      text,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
  }
}

/** The three default-role toggles shared by both editors. [chatOnClick] is null when not applicable. */
@Composable
private fun DefaultRolePickers(
  isChat: Boolean,
  isPopup: Boolean,
  isTitles: Boolean,
  onChat: () -> Unit,
  onPopup: (Boolean) -> Unit,
  onTitles: (Boolean) -> Unit,
) {
  SectionLabel(tr("manager.default_for", "Default for"))
  SettingsCard {
    Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onChat).padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(tr("manager.role_chat", "Chat"), style = MaterialTheme.typography.titleSmall)
        Text(tr("manager.role_chat_desc", "Use this model for new chats"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      androidx.compose.material3.RadioButton(selected = isChat, onClick = onChat)
    }
    RowDivider()
    ToggleItem(Icons.Rounded.Tune, tr("manager.role_popup", "Assistant popup"), tr("manager.role_popup_desc", "On = use here too; off = same as chat"), isPopup, onPopup)
    RowDivider()
    ToggleItem(Icons.Rounded.Description, tr("manager.role_titles", "Chat titles"), tr("manager.role_titles_desc", "Writes a short title after the first message"), isTitles, onTitles)
  }
}

/** Per-model config + defaults for an on-device model: render its tunables and persist on change. */
@Composable
private fun LocalModelEditorSheet(
  model: Model,
  state: ChatUiState,
  viewModel: MainChatViewModel,
  mmVm: ModelManagerViewModel,
  onClose: () -> Unit,
) {
  BackHandler { onClose() }
  val label = viewModel.displayNameFor(model.name, model.displayName.ifEmpty { model.name })
  val values =
    remember(model.name) {
      androidx.compose.runtime.snapshots.SnapshotStateMap<String, Any>().apply { putAll(model.configValues) }
    }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Text(label, style = MaterialTheme.typography.headlineSmall)
    Text(tr("model.on_device", "On-device"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (model.configs.isNotEmpty()) {
      SectionLabel(tr("manager.config", "Configuration"))
      SettingsCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          ConfigEditorsPanel(configs = model.configs, values = values)
          androidx.compose.material3.Button(
            onClick = {
              model.configValues = model.configValues + values
              mmVm.updateConfigValuesUpdateTrigger()
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(tr("common.save", "Save"))
          }
        }
      }
    }

    DefaultRolePickers(
      isChat = !state.isApiModel && state.modelName == label,
      isPopup = state.popupModel == "local:${model.name}",
      isTitles = state.titleModel == "local:${model.name}",
      onChat = { viewModel.setActiveModel(model) },
      onPopup = { on -> viewModel.setPopupModel(if (on) "local:${model.name}" else null) },
      onTitles = { on -> viewModel.setTitleModel(if (on) "local:${model.name}" else null) },
    )
  }
}

/** Per-model config + defaults for a remote model: edit its fields and persist via saveApiModel. */
@Composable
private fun RemoteModelEditorSheet(
  cfg: ApiModelConfig,
  state: ChatUiState,
  viewModel: MainChatViewModel,
  onClose: () -> Unit,
) {
  BackHandler { onClose() }
  var displayName by remember(cfg.id) { mutableStateOf(cfg.displayName) }
  var vision by remember(cfg.id) { mutableStateOf(cfg.supportsVision) }
  var temperature by remember(cfg.id) { mutableStateOf(cfg.temperature?.toString() ?: "") }
  var sysPrompt by remember(cfg.id) { mutableStateOf(cfg.systemPrompt) }

  fun persist() =
    viewModel.saveApiModel(
      cfg.copy(
        displayName = displayName.ifBlank { cfg.modelId }.trim(),
        supportsVision = vision,
        temperature = temperature.trim().toFloatOrNull(),
        systemPrompt = sysPrompt.trim(),
      )
    )

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Text(cfg.displayName, style = MaterialTheme.typography.headlineSmall)
    Text("${cfg.provider.label} · ${cfg.modelId}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    SectionLabel(tr("manager.config", "Configuration"))
    SettingsCard {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(displayName, { displayName = it }, label = { Text(tr("api.display_name", "Display name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(tr("api.supports_vision", "Supports vision"), modifier = Modifier.weight(1f))
          Switch(checked = vision, onCheckedChange = { vision = it })
        }
        OutlinedTextField(
          temperature,
          { temperature = it },
          label = { Text(tr("api.temperature", "Temperature (optional, e.g. 0.7)")) },
          singleLine = true,
          keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          sysPrompt,
          { sysPrompt = it },
          label = { Text(tr("api.system_prompt", "System prompt for this model (optional)")) },
          minLines = 2,
          modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.Button(onClick = { persist() }, modifier = Modifier.fillMaxWidth()) {
          Text(tr("common.save", "Save"))
        }
      }
    }

    DefaultRolePickers(
      isChat = state.isApiModel && state.modelName == cfg.displayName,
      isPopup = state.popupModel == "api:${cfg.id}",
      isTitles = state.titleModel == "api:${cfg.id}",
      onChat = { viewModel.setActiveApiModel(cfg) },
      onPopup = { on -> viewModel.setPopupModel(if (on) "api:${cfg.id}" else null) },
      onTitles = { on -> viewModel.setTitleModel(if (on) "api:${cfg.id}" else null) },
    )
  }
}

@Composable
private fun ModelStorageRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  present: Boolean,
  installing: Boolean,
  size: Long,
  onInstall: () -> Unit,
  onDelete: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.size(16.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(
        if (present) "$subtitle · ${fmtBytes(size)}" else subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.size(8.dp))
    when {
      installing ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          Text(tr("voices.installing", "Installing…"), style = MaterialTheme.typography.bodySmall)
        }
      present ->
        IconButton(onClick = onDelete) {
          Icon(Icons.Rounded.Delete, contentDescription = tr("common.delete", "Delete"), modifier = Modifier.size(20.dp))
        }
      else ->
        androidx.compose.material3.Button(onClick = onInstall) { Text(tr("models.install", "Install")) }
    }
  }
}

/** Clean on-device LLM download/manage — Gemma models + import (Hugging Face / local file). */
@Composable
private fun LlmModelsPage(mmVm: ModelManagerViewModel) {
  val context = LocalContext.current
  val mm by mmVm.uiState.collectAsState()
  val models =
    remember(mm.modelDownloadStatus, mm.modelImportingUpdateTrigger) {
      mmVm.getAllModels().filter { it.isLlm }
    }
  val unsupportedMsg =
    tr("llm.unsupported_file", "Unsupported file — pick a .litertlm (or .task) model.")

  var showHfDialog by remember { mutableStateOf(false) }
  var hfUrl by remember { mutableStateOf("") }
  val selectedUri = remember { mutableStateOf<Uri?>(null) }
  val selectedInfo = remember { mutableStateOf<ImportedModel?>(null) }
  var showImportDialog by remember { mutableStateOf(false) }
  var showImportingDialog by remember { mutableStateOf(false) }

  val filePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          val name = fileNameOf(context, uri).orEmpty().lowercase()
          if ((name.endsWith(".task") || name.endsWith(".litertlm")) && !name.contains("-web")) {
            selectedUri.value = uri
            showImportDialog = true
          } else {
            android.widget.Toast.makeText(context, unsupportedMsg, android.widget.Toast.LENGTH_LONG).show()
          }
        }
      }
    }

  if (showImportDialog) {
    selectedUri.value?.let { uri ->
      ModelImportDialog(
        uri = uri,
        onDismiss = { showImportDialog = false },
        onDone = { info ->
          selectedInfo.value = info
          showImportDialog = false
          showImportingDialog = true
        },
      )
    }
  }
  if (showImportingDialog) {
    selectedUri.value?.let { uri ->
      selectedInfo.value?.let { info ->
        ModelImportingDialog(
          uri = uri,
          info = info,
          onDismiss = { showImportingDialog = false },
          onDone = {
            mmVm.addImportedLlmModel(info = it)
            showImportingDialog = false
          },
        )
      }
    }
  }
  if (showHfDialog) {
    AlertDialog(
      onDismissRequest = { showHfDialog = false },
      title = { Text(tr("llm.import_hf_title", "Import from Hugging Face")) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            tr("llm.import_hf_desc", "Paste a direct link to a .litertlm model file on Hugging Face."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
            value = hfUrl,
            onValueChange = { hfUrl = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://huggingface.co/…/model.litertlm") },
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val url = hfUrl.trim()
            showHfDialog = false
            if (url.isNotEmpty()) {
              val uri = url.toUri()
              if (fileNameOf(context, uri).orEmpty().lowercase().endsWith(".litertlm")) {
                selectedUri.value = uri
                showImportDialog = true
              } else {
                android.widget.Toast.makeText(context, unsupportedMsg, android.widget.Toast.LENGTH_LONG).show()
              }
            }
          }
        ) {
          Text(tr("common.add", "Add"))
        }
      },
      dismissButton = { TextButton(onClick = { showHfDialog = false }) { Text(tr("common.cancel", "Cancel")) } },
    )
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      tr("llm.desc", "Download a Gemma model to chat fully on-device."),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Import: a Hugging Face button (its 🤗 mark) and a local-file button.
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(onClick = { showHfDialog = true }, modifier = Modifier.weight(1f)) {
        Text("🤗", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(tr("llm.hugging_face", "Hugging Face"))
      }
      OutlinedButton(
        onClick = {
          val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
              addCategory(Intent.CATEGORY_OPENABLE)
              type = "*/*"
              putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            }
          filePicker.launch(intent)
        },
        modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(tr("llm.local_file", "Local file"))
      }
    }
    models.forEach { model ->
      val st = mm.modelDownloadStatus[model.name]
      val bytes = if (model.totalBytes > 0) model.totalBytes else model.sizeInBytes
      val inProgress =
        st?.status == ModelDownloadStatusType.IN_PROGRESS ||
          st?.status == ModelDownloadStatusType.UNZIPPING ||
          st?.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED
      val installed = model.imported || st?.status == ModelDownloadStatusType.SUCCEEDED
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(model.displayName.ifEmpty { model.name }, style = MaterialTheme.typography.titleSmall)
          Text(
            if (model.imported) tr("llm.imported", "Imported") else fmtBytes(bytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        when {
          inProgress -> {
            val pct = if (st!!.totalBytes > 0) (st.receivedBytes * 100 / st.totalBytes).toInt() else 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
              Text(
                if (st.status == ModelDownloadStatusType.UNZIPPING) tr("voices.installing", "Installing…") else "$pct%",
                style = MaterialTheme.typography.bodySmall,
              )
              TextButton(onClick = { mmVm.cancelDownloadModel(model) }) { Text(tr("common.cancel", "Cancel")) }
            }
          }
          installed ->
            IconButton(onClick = { mmVm.deleteModel(model) }) {
              Icon(Icons.Rounded.Delete, contentDescription = tr("common.delete", "Delete"), modifier = Modifier.size(20.dp))
            }
          else ->
            androidx.compose.material3.Button(
              onClick = { mmVm.downloadModel(mmVm.getTaskById(BuiltInTaskId.LLM_CHAT), model) }
            ) {
              Text(tr("common.download", "Download"))
            }
        }
      }
    }
  }
}

/** Display name for a content/web [uri], for validating the file extension. */
private fun fileNameOf(context: Context, uri: Uri): String? {
  if (uri.scheme == "content") {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i != -1) return cursor.getString(i)
      }
    }
  }
  return uri.lastPathSegment
}

@Composable
private fun DefaultRadioRow(label: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyLarge)
      if (subtitle != null) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}


/** Voice manager: install/remove on-device voices and pick the active one. Rendered inside a card. */
@Composable
private fun VoicesSection(state: ChatUiState, viewModel: MainChatViewModel) {
  val installed = TTS_VOICES.filter { state.installedVoices.contains(it.locale) }
  val available = TTS_VOICES.filterNot { state.installedVoices.contains(it.locale) }
  Column(
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      tr("voices.desc", "Install a voice to have replies read aloud — fully on-device, even without Google services. Tap an installed voice to make it active."),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // System engine is the default where it exists; on GrapheneOS there's none, so it's hidden.
    if (viewModel.systemTtsAvailable()) {
      Row(
        modifier = Modifier.fillMaxWidth().clickable { viewModel.selectVoice(null) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        androidx.compose.material3.RadioButton(selected = state.ttsVoice == null, onClick = { viewModel.selectVoice(null) })
        Column(Modifier.weight(1f)) {
          Text(tr("voices.system_default", "System voice"), style = MaterialTheme.typography.bodyLarge)
          Text(
            tr("voices.system_default_desc", "Use the device's built-in text-to-speech"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    if (installed.isNotEmpty()) {
      Text(
        tr("voices.installed", "Installed"),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 10.dp),
      )
      installed.forEach { voice ->
        Row(
          modifier = Modifier.fillMaxWidth().clickable { viewModel.selectVoice(voice.locale) }.padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          androidx.compose.material3.RadioButton(
            selected = state.ttsVoice == voice.locale,
            onClick = { viewModel.selectVoice(voice.locale) },
          )
          Text(voice.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
          IconButton(onClick = { viewModel.deleteVoice(voice.locale) }) {
            Icon(Icons.Rounded.Delete, contentDescription = tr("voices.delete_cd", "Remove voice"), modifier = Modifier.size(20.dp))
          }
        }
      }
    }

    Text(
      tr("voices.available", "Available"),
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.padding(top = 10.dp),
    )
    available.forEach { voice ->
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(voice.name, style = MaterialTheme.typography.bodyLarge)
          Text("~${voice.approxMb} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.downloadingVoice == voice.locale) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
              when {
                state.voiceProgress >= 0.999f -> tr("voices.installing", "Installing…")
                state.voiceProgress > 0f -> "${(state.voiceProgress * 100).toInt()}%"
                else -> tr("voices.downloading", "Downloading…")
              },
              style = MaterialTheme.typography.bodySmall,
            )
          }
        } else {
          androidx.compose.material3.Button(
            onClick = { viewModel.downloadVoice(voice) },
            enabled = state.downloadingVoice == null,
          ) {
            Text(tr("common.download", "Download"))
          }
        }
      }
    }
  }
}

@Composable
private fun DocumentsPage(state: ChatUiState, viewModel: MainChatViewModel) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var title by remember { mutableStateOf("") }
  var text by remember { mutableStateOf("") }
  val filePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      if (uri != null) {
        scope.launch {
          val content =
            kotlinx.coroutines.withContext(Dispatchers.IO) {
              runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
            }
          if (!content.isNullOrBlank()) viewModel.addRagDocument("Imported document", content)
        }
      }
    }
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(tr("docs.use_documents", "Use my documents"), style = MaterialTheme.typography.titleSmall)
        Text(tr("docs.use_documents_desc", "Lightweight retrieval — low memory."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Switch(checked = state.ragEnabled, onCheckedChange = viewModel::setRagEnabled)
    }
    OutlinedTextField(title, { title = it }, label = { Text(tr("docs.title_hint", "Title")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(text, { text = it }, label = { Text(tr("docs.paste_text", "Paste text")) }, minLines = 4, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      androidx.compose.material3.Button(
        onClick = {
          if (text.isNotBlank()) {
            viewModel.addRagDocument(title.ifBlank { "Note" }, text)
            title = ""
            text = ""
          }
        },
        enabled = text.isNotBlank(),
      ) {
        Text(tr("common.add", "Add"))
      }
      OutlinedButton(onClick = { filePicker.launch("text/*") }) { Text(tr("docs.import_txt", "Import .txt")) }
    }
    if (state.ragDocs.isNotEmpty()) {
      Text(tr("docs.documents", "Documents"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
      state.ragDocs.forEach { doc ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("${doc.title} (${doc.chunks})", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
          IconButton(onClick = { viewModel.deleteRagDocument(doc.id) }) {
            Icon(Icons.Rounded.Delete, contentDescription = tr("common.delete", "Delete"), modifier = Modifier.size(20.dp))
          }
        }
      }
    }
  }
}
