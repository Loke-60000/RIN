// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kaiwa.i18n.tr

/**
 * One page for everything about a provider: its account (base URL + key) and which of its models are
 * available to chat. Each saved credential is a card you can expand to manage its models in either
 * Auto mode (every model the endpoint lists is on) or Manual mode (pick them one by one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProvidersPage(state: ChatUiState, viewModel: MainChatViewModel) {
  var editing by remember { mutableStateOf<ApiCredential?>(null) }
  var creating by remember { mutableStateOf(false) }

  if (creating || editing != null) {
    ProviderEditor(
      existing = editing,
      onCancel = { creating = false; editing = null },
      onSave = {
        viewModel.saveApiCredential(it)
        creating = false
        editing = null
      },
      onDelete = {
        editing?.let { e -> viewModel.deleteApiCredential(e.id) }
        creating = false
        editing = null
      },
    )
    return
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      tr("provider.desc", "A provider is an account or endpoint — its base URL and API key. Add it once, then choose which of its models you can chat with."),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.apiCredentials.isEmpty()) {
      Text(
        tr("provider.none_yet", "No providers yet. Add one to use OpenAI, Anthropic, Gemini, Ollama, OpenRouter, etc."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    state.apiCredentials.forEach { cred ->
      ProviderCard(cred, state, viewModel, onEdit = { editing = cred })
    }
    androidx.compose.material3.Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
      Text(tr("provider.add", "Add provider"))
    }
  }
}

private fun maskKey(key: String): String =
  when {
    key.isBlank() -> "no key"
    key.length <= 4 -> "••••"
    else -> "••••" + key.takeLast(4)
  }

/** Models currently enabled for a credential (matched by endpoint + key, as that's the wire identity). */
private fun enabledModelsFor(cred: ApiCredential, state: ChatUiState): List<ApiModelConfig> =
  state.apiModels.filter { it.baseUrl == cred.baseUrl && it.apiKey == cred.apiKey }

/** A provider account plus its model enablement, expandable in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(
  cred: ApiCredential,
  state: ChatUiState,
  viewModel: MainChatViewModel,
  onEdit: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  val fetched = state.fetchedByProvider[cred.id]
  val fetching = state.fetchingProvider == cred.id
  val enabledHere = enabledModelsFor(cred, state)

  // Opening the card loads the endpoint's model list once; auto creds then flip them all on.
  LaunchedEffect(open) { if (open && fetched == null && !fetching) viewModel.fetchModels(cred) }
  LaunchedEffect(open, cred.autoEnableModels, fetched) {
    if (open && cred.autoEnableModels && fetched != null) viewModel.enableAllApiModels(cred, fetched)
  }

  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(cred.name.ifBlank { cred.provider.label }, style = MaterialTheme.typography.titleSmall)
          val mode = if (cred.autoEnableModels) tr("provider.auto", "Auto") else tr("provider.manual", "Manual")
          Text(
            "${cred.provider.label} · ${maskKey(cred.apiKey)} · $mode" +
              if (enabledHere.isNotEmpty()) " · ${enabledHere.size} on" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onEdit) {
          Icon(Icons.Rounded.Edit, contentDescription = tr("common.edit", "Edit"), modifier = Modifier.size(20.dp))
        }
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
      }

      if (open) {
        Spacer(Modifier.size(10.dp))
        ProviderModeSelector(cred, viewModel)
        Spacer(Modifier.size(10.dp))
        when {
          fetching ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
              Text(tr("api.loading_models", "Loading models…"), style = MaterialTheme.typography.bodySmall)
            }
          cred.autoEnableModels -> AutoModelsSummary(fetched.orEmpty().size)
          else -> ManualModelsList(cred, state, viewModel, fetched.orEmpty())
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModeSelector(cred: ApiCredential, viewModel: MainChatViewModel) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    FilterChip(
      selected = cred.autoEnableModels,
      onClick = { if (!cred.autoEnableModels) viewModel.saveApiCredential(cred.copy(autoEnableModels = true)) },
      label = { Text(tr("provider.auto", "Auto")) },
    )
    FilterChip(
      selected = !cred.autoEnableModels,
      onClick = { if (cred.autoEnableModels) viewModel.saveApiCredential(cred.copy(autoEnableModels = false)) },
      label = { Text(tr("provider.manual", "Manual")) },
    )
  }
}

@Composable
private fun AutoModelsSummary(count: Int) {
  Text(
    if (count > 0)
      tr("provider.auto_count", "All %d models from this endpoint are available.").format(count)
    else tr("provider.auto_none", "No models reported yet — they turn on automatically once listed."),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun ManualModelsList(
  cred: ApiCredential,
  state: ChatUiState,
  viewModel: MainChatViewModel,
  ids: List<String>,
) {
  var customId by remember { mutableStateOf("") }
  if (ids.isEmpty()) {
    Text(
      tr("api.none_listed", "This provider didn't list any models — add one by id below."),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  ids.forEach { id ->
    val enabled = state.apiModels.any { it.modelId == id && it.baseUrl == cred.baseUrl && it.apiKey == cred.apiKey }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(id, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      Switch(checked = enabled, onCheckedChange = { viewModel.setApiModelEnabled(cred, id, it) })
    }
  }
  Spacer(Modifier.size(4.dp))
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
      value = customId,
      onValueChange = { customId = it },
      modifier = Modifier.weight(1f),
      singleLine = true,
      placeholder = { Text(tr("api.custom_model_id", "Custom model id")) },
    )
    TextButton(
      enabled = customId.isNotBlank(),
      onClick = {
        viewModel.setApiModelEnabled(cred, customId.trim(), true)
        customId = ""
      },
    ) {
      Text(tr("common.add", "Add"))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditor(
  existing: ApiCredential?,
  onCancel: () -> Unit,
  onSave: (ApiCredential) -> Unit,
  onDelete: () -> Unit,
) {
  var provider by remember { mutableStateOf(existing?.provider ?: ApiProvider.OPENAI) }
  var name by remember { mutableStateOf(existing?.name ?: "") }
  var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: provider.defaultBaseUrl) }
  var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }
  var auto by remember { mutableStateOf(existing?.autoEnableModels ?: true) }
  var providerMenu by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(tr("provider.label", "Provider"), style = MaterialTheme.typography.labelLarge)
    Box {
      OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
        Text(provider.label)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
      }
      DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
        ApiProvider.values().forEach { p ->
          DropdownMenuItem(
            text = { Text(p.label) },
            onClick = {
              provider = p
              if (baseUrl.isBlank() || ApiProvider.values().any { it.defaultBaseUrl == baseUrl }) {
                baseUrl = p.defaultBaseUrl
              }
              providerMenu = false
            },
          )
        }
      }
    }
    OutlinedTextField(name, { name = it }, label = { Text(tr("provider.name_hint", "Name (e.g. My OpenAI)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text(tr("provider.base_url", "Base URL")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(apiKey, { apiKey = it }, label = { Text(tr("provider.api_key", "API key")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(tr("provider.auto_enable", "Auto-enable models"), style = MaterialTheme.typography.titleSmall)
        Text(
          tr("provider.auto_enable_desc", "Turn on every model the endpoint lists. Off lets you pick them by hand."),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(checked = auto, onCheckedChange = { auto = it })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      androidx.compose.material3.Button(
        onClick = {
          onSave(
            ApiCredential(
              id = existing?.id ?: java.util.UUID.randomUUID().toString(),
              provider = provider,
              name = name.ifBlank { provider.label }.trim(),
              baseUrl = baseUrl.ifBlank { provider.defaultBaseUrl },
              apiKey = apiKey.trim(),
              autoEnableModels = auto,
            )
          )
        },
        enabled = apiKey.isNotBlank() || !provider.needsKey,
      ) {
        Text(tr("common.save", "Save"))
      }
      OutlinedButton(onClick = onCancel) { Text(tr("common.cancel", "Cancel")) }
      if (existing != null) {
        TextButton(onClick = onDelete) { Text(tr("common.delete", "Delete"), color = MaterialTheme.colorScheme.error) }
      }
    }
  }
}

/**
 * Editor for a single remote model's config (display name, vision, temperature, system prompt). The
 * credential picker lets you attach it to a saved provider or create a new one on the spot. Reused by
 * the Model manager when editing a cloud model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApiModelEditor(
  existing: ApiModelConfig?,
  credentials: List<ApiCredential>,
  onSaveCredential: (ApiCredential) -> Unit,
  onCancel: () -> Unit,
  onSave: (ApiModelConfig) -> Unit,
  onDelete: () -> Unit,
) {
  val NEW = "__new__"
  // Pick the credential matching the existing model, else default to the first saved one.
  val matchedCredId =
    existing?.let { e -> credentials.firstOrNull { it.provider == e.provider && it.apiKey == e.apiKey && it.baseUrl == e.baseUrl }?.id }
  var credId by remember { mutableStateOf(matchedCredId ?: credentials.firstOrNull()?.id ?: NEW) }
  var credMenu by remember { mutableStateOf(false) }

  // New-credential fields.
  var provider by remember { mutableStateOf(existing?.provider ?: ApiProvider.OPENAI) }
  var credName by remember { mutableStateOf("") }
  var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: provider.defaultBaseUrl) }
  var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }
  var providerMenu by remember { mutableStateOf(false) }

  // Model fields.
  var displayName by remember { mutableStateOf(existing?.displayName ?: "") }
  var modelId by remember { mutableStateOf(existing?.modelId ?: "") }
  var vision by remember { mutableStateOf(existing?.supportsVision ?: false) }
  var temperature by remember { mutableStateOf(existing?.temperature?.toString() ?: "") }
  var sysPrompt by remember { mutableStateOf(existing?.systemPrompt ?: "") }

  val usingNew = credId == NEW
  val selectedCred = credentials.firstOrNull { it.id == credId }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(tr("api.credential", "Credential"), style = MaterialTheme.typography.labelLarge)
    Box {
      OutlinedButton(onClick = { credMenu = true }, modifier = Modifier.fillMaxWidth()) {
        Text(selectedCred?.let { "${it.name} (${it.provider.label})" } ?: tr("api.new_credential", "New credential"))
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
      }
      DropdownMenu(expanded = credMenu, onDismissRequest = { credMenu = false }) {
        credentials.forEach { c ->
          DropdownMenuItem(text = { Text("${c.name} (${c.provider.label})") }, onClick = { credId = c.id; credMenu = false })
        }
        DropdownMenuItem(text = { Text(tr("api.new_credential_menu", "New credential…")) }, onClick = { credId = NEW; credMenu = false })
      }
    }

    if (usingNew) {
      Text(tr("provider.label", "Provider"), style = MaterialTheme.typography.labelLarge)
      Box {
        OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
          Text(provider.label)
          Spacer(Modifier.weight(1f))
          Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
          ApiProvider.values().forEach { p ->
            DropdownMenuItem(
              text = { Text(p.label) },
              onClick = {
                provider = p
                if (baseUrl.isBlank() || ApiProvider.values().any { it.defaultBaseUrl == baseUrl }) baseUrl = p.defaultBaseUrl
                providerMenu = false
              },
            )
          }
        }
      }
      OutlinedTextField(credName, { credName = it }, label = { Text(tr("api.cred_name_hint", "Credential name (e.g. My OpenAI)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text(tr("provider.base_url", "Base URL")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(apiKey, { apiKey = it }, label = { Text(tr("api.api_key_reused", "API key (saved once, reused)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }

    OutlinedTextField(displayName, { displayName = it }, label = { Text(tr("api.display_name", "Display name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(modelId, { modelId = it }, label = { Text(tr("api.model_id", "Model id (e.g. gpt-4o, claude-3-5-sonnet)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(tr("api.supports_vision", "Supports vision"), modifier = Modifier.weight(1f))
      Switch(checked = vision, onCheckedChange = { vision = it })
    }
    OutlinedTextField(
      temperature,
      { temperature = it },
      label = { Text(tr("api.temperature", "Temperature (optional, e.g. 0.7)")) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      sysPrompt,
      { sysPrompt = it },
      label = { Text(tr("api.system_prompt", "System prompt for this model (optional)")) },
      minLines = 2,
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      tr("api.system_prompt_hint", "Leave the system prompt blank to use the app's default prompt."),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      androidx.compose.material3.Button(
        onClick = {
          // Resolve provider/baseUrl/key from the chosen (or new) credential.
          val resolvedProvider: ApiProvider
          val resolvedBase: String
          val resolvedKey: String
          if (usingNew) {
            val cred =
              ApiCredential(
                id = java.util.UUID.randomUUID().toString(),
                provider = provider,
                name = credName.ifBlank { provider.label }.trim(),
                baseUrl = baseUrl.ifBlank { provider.defaultBaseUrl },
                apiKey = apiKey.trim(),
              )
            onSaveCredential(cred)
            resolvedProvider = cred.provider
            resolvedBase = cred.baseUrl
            resolvedKey = cred.apiKey
          } else {
            resolvedProvider = selectedCred!!.provider
            resolvedBase = selectedCred.baseUrl
            resolvedKey = selectedCred.apiKey
          }
          val name = displayName.ifBlank { "${resolvedProvider.label} $modelId" }.trim()
          onSave(
            ApiModelConfig(
              id = existing?.id ?: java.util.UUID.randomUUID().toString(),
              provider = resolvedProvider,
              displayName = name,
              baseUrl = resolvedBase,
              apiKey = resolvedKey,
              modelId = modelId.trim(),
              supportsVision = vision,
              temperature = temperature.trim().toFloatOrNull(),
              systemPrompt = sysPrompt.trim(),
            )
          )
        },
        enabled = modelId.isNotBlank() && (!usingNew || apiKey.isNotBlank() || !provider.needsKey),
      ) {
        Text(tr("common.save", "Save"))
      }
      OutlinedButton(onClick = onCancel) { Text(tr("common.cancel", "Cancel")) }
      if (existing != null) {
        TextButton(onClick = onDelete) { Text(tr("common.delete", "Delete"), color = MaterialTheme.colorScheme.error) }
      }
    }
  }
}
