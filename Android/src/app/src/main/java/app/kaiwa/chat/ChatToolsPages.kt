// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kaiwa.i18n.tr
import app.kaiwa.proto.Skill

/**
 * MCP servers settings page. Lists each connected (or failed) server with an enable toggle and an
 * expandable list of its tools (enable + always-allow per tool), plus add/remove affordances. Matches
 * the Settings design system: gray intro text, white grouped cards, [ToolsSectionLabel] headers.
 */
@Composable
internal fun McpServersPage(state: ChatUiState, viewModel: MainChatViewModel) {
  val mcp by viewModel.mcpState.collectAsState()
  var showAdd by remember { mutableStateOf(false) }

  if (showAdd) {
    AddMcpServerDialog(
      onDismiss = { showAdd = false },
      onConfirm = { url, headerName, headerValue ->
        viewModel.addMcpServer(url, headerName, headerValue)
        showAdd = false
      },
    )
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      tr(
        "mcp.desc",
        "Connect external tool servers (Model Context Protocol) the on-device model can call to act on your behalf.",
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (mcp.mcpServers.isEmpty()) {
      Text(
        tr("mcp.empty", "No servers yet. Add one to give the model extra tools."),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
      )
    } else {
      mcp.mcpServers.forEach { server -> McpServerCard(server, viewModel) }
    }

    OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
      Text(tr("mcp.add_server", "Add server"))
    }
  }
}

@Composable
private fun McpServerCard(
  server: app.kaiwa.chat.tools.McpServerState,
  viewModel: MainChatViewModel,
) {
  val proto = server.mcpServer
  var expanded by remember { mutableStateOf(false) }
  val statusText =
    when {
      server.error != null -> tr("mcp.status_error", "Connection error")
      server.client != null -> tr("mcp.status_connected", "Connected")
      else -> tr("mcp.status_offline", "Offline")
    }
  val statusColor =
    if (server.error != null) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary

  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          proto.name.ifEmpty { proto.url },
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Medium,
        )
        Text(proto.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(server.error ?: statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
      }
      Switch(
        checked = proto.enabled,
        onCheckedChange = { viewModel.setMcpServerEnabled(proto.url, it) },
      )
      IconButton(onClick = { viewModel.removeMcpServer(proto.url) }) {
        Icon(Icons.Rounded.Delete, contentDescription = tr("common.delete", "Delete"), modifier = Modifier.size(20.dp))
      }
    }
    if (proto.toolsList.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          tr("mcp.tools_count", "Tools") + " (${proto.toolsList.size})",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.weight(1f),
        )
        Icon(
          if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (expanded) {
        proto.toolsList.forEach { tool ->
          Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(tool.name, style = MaterialTheme.typography.bodyLarge)
                if (tool.description.isNotBlank()) {
                  Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Switch(
                checked = tool.enabled,
                onCheckedChange = { viewModel.setMcpToolEnabled(proto.url, tool.name, it) },
              )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
              Text(
                tr("mcp.always_allow", "Always allow"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
              )
              Switch(
                checked = tool.alwaysAllow,
                enabled = tool.enabled,
                onCheckedChange = { viewModel.setMcpToolAlwaysAllow(proto.url, tool.name, it) },
              )
            }
          }
        }
      }
    }
  }
}

/** Add-server dialog: a URL field plus an optional single request-header auth (name + value). */
@Composable
private fun AddMcpServerDialog(
  onDismiss: () -> Unit,
  onConfirm: (url: String, headerName: String?, headerValue: String?) -> Unit,
) {
  var url by remember { mutableStateOf("") }
  var headerName by remember { mutableStateOf("") }
  var headerValue by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(tr("mcp.add_server", "Add server")) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          tr("mcp.add_desc", "Paste the server's URL. Add an auth header only if the server requires one."),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text(tr("mcp.server_url", "Server URL")) },
          placeholder = { Text("https://…") },
        )
        OutlinedTextField(
          value = headerName,
          onValueChange = { headerName = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text(tr("mcp.auth_header_name", "Auth header name (optional)")) },
        )
        OutlinedTextField(
          value = headerValue,
          onValueChange = { headerValue = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text(tr("mcp.auth_header_value", "Auth header value (optional)")) },
        )
      }
    },
    confirmButton = {
      TextButton(
        enabled = url.trim().isNotEmpty(),
        onClick = { onConfirm(url.trim(), headerName.ifBlank { null }, headerValue.ifBlank { null }) },
      ) {
        Text(tr("common.add", "Add"))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(tr("common.cancel", "Cancel")) } },
  )
}

/**
 * Skills settings page. Lists built-in and custom skills, each with a select toggle. Selecting a
 * skill makes its instructions loadable by the model (via the load_skill tool).
 */
@Composable
internal fun SkillsPage(state: ChatUiState, viewModel: MainChatViewModel) {
  val skills by viewModel.skills.collectAsState()
  val builtIn = skills.filter { it.builtIn }
  val custom = skills.filterNot { it.builtIn }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      tr(
        "skills.desc",
        "Skills are instruction packs the model can load on demand to complete specialized tasks. Turn on the ones you want available.",
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (skills.isEmpty()) {
      Text(
        tr("skills.empty", "No skills available."),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
      )
    }

    if (builtIn.isNotEmpty()) {
      ToolsSectionLabel(tr("skills.built_in", "Built-in"))
      builtIn.forEach { SkillRow(it, viewModel) }
    }
    if (custom.isNotEmpty()) {
      ToolsSectionLabel(tr("skills.custom", "Custom"))
      custom.forEach { SkillRow(it, viewModel) }
    }
    // Custom-skill import by URL stays in the agentchat manager for now; not surfaced here yet.
  }
}

@Composable
private fun SkillRow(skill: Skill, viewModel: MainChatViewModel) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(skill.name, style = MaterialTheme.typography.bodyLarge)
      if (skill.description.isNotBlank()) {
        Text(
          skill.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Spacer(Modifier.size(8.dp))
    Switch(
      checked = skill.selected,
      onCheckedChange = { viewModel.setSkillSelected(skill, it) },
    )
  }
}

/** Section header matching the Settings look (the private one in ChatSettingsPages). */
@Composable
private fun ToolsSectionLabel(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
  )
}

/**
 * The MCP tool-call confirmation dialog. Shows the tool name and (pretty-printed) arguments with
 * Allow once / Allow always / Deny. Resolving routes back through [MainChatViewModel.resolvePermission].
 */
@Composable
internal fun McpToolPermissionDialog(
  toolName: String,
  argument: String,
  onResult: (app.kaiwa.chat.tools.PermissionDecision) -> Unit,
) {
  val pretty =
    remember(argument) {
      try {
        org.json.JSONObject(argument).toString(2)
      } catch (e: Exception) {
        argument
      }
    }
  AlertDialog(
    onDismissRequest = { onResult(app.kaiwa.chat.tools.PermissionDecision.DENY) },
    title = { Text(tr("mcp.permission_title", "Run this tool?")) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(tr("mcp.permission_tool", "Tool"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
          Text(toolName, style = MaterialTheme.typography.bodyMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(tr("mcp.permission_input", "Input"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
          Text(
            pretty,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
          )
        }
      }
    },
    confirmButton = {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
          onClick = { onResult(app.kaiwa.chat.tools.PermissionDecision.ALWAYS_ALLOW) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(tr("mcp.permission_always", "Always allow"))
        }
        Button(
          onClick = { onResult(app.kaiwa.chat.tools.PermissionDecision.ALLOW_ONCE) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(tr("mcp.permission_once", "Allow once"))
        }
        OutlinedButton(
          onClick = { onResult(app.kaiwa.chat.tools.PermissionDecision.DENY) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(tr("mcp.permission_deny", "Don't allow"))
        }
      }
    },
  )
}
