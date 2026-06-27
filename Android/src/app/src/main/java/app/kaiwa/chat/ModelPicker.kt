// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kaiwa.data.Model
import app.kaiwa.i18n.tr

/** Gemini-style model picker: a bottom sheet of on-device + cloud models with the active one marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
  downloaded: List<Model>,
  state: ChatUiState,
  displayNameFor: (String, String) -> String,
  onDismiss: () -> Unit,
  onSelectLocal: (Model) -> Unit,
  onSelectApi: (ApiModelConfig) -> Unit,
  onUnload: () -> Unit,
  onManage: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
      Text(
        tr("model.choose", "Choose a model"),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
      )
      if (downloaded.isEmpty() && state.apiModels.isEmpty()) {
        Text(
          tr("model.no_models_yet", "No models yet — download one or add a provider."),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
      }
      downloaded.forEach { m ->
        val label = displayNameFor(m.name, m.displayName.ifEmpty { m.name })
        ModelSheetRow(
          icon = Icons.Rounded.Memory,
          title = label,
          subtitle = tr("model.on_device", "On-device"),
          selected = label == state.modelName && !state.isApiModel,
        ) {
          onSelectLocal(m)
        }
      }
      state.apiModels.forEach { cfg ->
        ModelSheetRow(
          icon = Icons.Rounded.Cloud,
          title = cfg.displayName,
          subtitle = cfg.provider.label,
          selected = cfg.displayName == state.modelName && state.isApiModel,
        ) {
          onSelectApi(cfg)
        }
      }
      HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
      ModelSheetRow(Icons.Rounded.Download, tr("model.manage", "Download / manage models"), null, selected = false) { onManage() }
      if (!state.isApiModel && state.phase != ChatPhase.NO_MODEL) {
        ModelSheetRow(
          icon = Icons.Rounded.Close,
          title = if (state.phase == ChatPhase.INITIALIZING) tr("model.stop_loading", "Stop loading") else tr("model.unload", "Unload model"),
          subtitle = null,
          selected = false,
        ) {
          onUnload()
        }
      }
    }
  }
}

@Composable
internal fun ModelSheetRow(
  icon: ImageVector,
  title: String,
  subtitle: String?,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 2.dp)
        .clip(RoundedCornerShape(18.dp))
        .clickable { onClick() }
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier =
        Modifier.size(40.dp)
          .clip(CircleShape)
          .background(
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
          ),
    ) {
      Icon(
        icon,
        contentDescription = null,
        tint =
          if (selected) MaterialTheme.colorScheme.onPrimaryContainer
          else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
      )
    }
    Spacer(Modifier.width(14.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      )
      if (subtitle != null) {
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (selected) {
      Icon(
        Icons.Rounded.Check,
        contentDescription = tr("model.active_cd", "Active"),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}
