// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Optional call-to-action shown beneath an [EmptyState]. */
data class EmptyStateButtonConfig(
  @StringRes val buttonLabelResId: Int,
  val buttonIcon: ImageVector? = null,
  val onButtonClick: () -> Unit = {},
  val extraContent: @Composable () -> Unit = {},
)

/** Centered icon + title + description placeholder, with an optional action button. */
@Composable
fun EmptyState(
  icon: ImageVector,
  @StringRes titleResId: Int,
  @StringRes descriptionResId: Int,
  buttonConfig: EmptyStateButtonConfig? = null,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
    modifier = Modifier.padding(horizontal = 48.dp),
  ) {
    Icon(
      icon,
      contentDescription = null,
      modifier = Modifier.size(56.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      stringResource(titleResId),
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Text(
      stringResource(descriptionResId),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (buttonConfig != null) {
      Box {
        Button(
          contentPadding = SMALL_BUTTON_CONTENT_PADDING,
          onClick = buttonConfig.onButtonClick,
        ) {
          buttonConfig.buttonIcon?.let { buttonIcon ->
            Icon(
              buttonIcon,
              contentDescription = null,
              modifier = Modifier.padding(end = 8.dp).size(20.dp),
            )
          }
          Text(stringResource(buttonConfig.buttonLabelResId))
        }
        buttonConfig.extraContent()
      }
    }
  }
}
