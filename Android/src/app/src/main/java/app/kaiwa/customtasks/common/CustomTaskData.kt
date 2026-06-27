// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.customtasks.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaiwa.ui.modelmanager.ModelManagerViewModel

/**
 * Payload handed to a custom task's `MainScreen` composable.
 *
 * @param modelManagerViewModel Access to model state and lifecycle.
 * @param bottomPadding Padding to reserve at the bottom of the content (the screen otherwise draws
 *   to the bottom edge).
 * @param setAppBarControlsDisabled Toggles whether app-bar controls (back, config, etc.) are
 *   enabled.
 * @param setTopBarVisible Shows or hides the top bar.
 * @param setCustomNavigateUpCallback Overrides the up-navigation behavior, or clears it with null.
 */
data class CustomTaskData(
  val modelManagerViewModel: ModelManagerViewModel,
  val bottomPadding: Dp = 0.dp,
  val setAppBarControlsDisabled: (Boolean) -> Unit = {},
  val setTopBarVisible: (Boolean) -> Unit = {},
  val setCustomNavigateUpCallback: ((() -> Unit)?) -> Unit = {},
)

/**
 * Payload handed to a built-in task screen.
 *
 * @param initialQuery Optional prompt to dispatch automatically once the screen loads.
 */
data class CustomTaskDataForBuiltinTask(
  val modelManagerViewModel: ModelManagerViewModel,
  val onNavUp: () -> Unit,
  val initialQuery: String? = null,
)
