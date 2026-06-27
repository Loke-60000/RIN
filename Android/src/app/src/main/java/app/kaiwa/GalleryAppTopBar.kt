// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

@file:OptIn(ExperimentalMaterial3Api::class)

package app.kaiwa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kaiwa.data.AppBarAction
import app.kaiwa.data.AppBarActionType

/**
 * Center-aligned top bar shared across screens.
 *
 * The title auto-sizes within a small range so longer titles still fit on one line, and the app
 * logo is prepended only when the title equals the app name. [leftAction] and [rightAction] drive
 * the optional navigation icon and trailing control respectively.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryTopAppBar(
  title: String,
  modifier: Modifier = Modifier,
  leftAction: AppBarAction? = null,
  rightAction: AppBarAction? = null,
  scrollBehavior: TopAppBarScrollBehavior? = null,
  subtitle: String = "",
) {
  val titleColor = MaterialTheme.colorScheme.onSurface
  CenterAlignedTopAppBar(
    modifier = modifier,
    scrollBehavior = scrollBehavior,
    title = { TitleBlock(title = title, subtitle = subtitle, titleColor = titleColor) },
    navigationIcon = { LeadingAction(leftAction) },
    actions = { TrailingAction(rightAction) },
  )
}

@Composable
private fun TitleBlock(title: String, subtitle: String, titleColor: Color) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (title == stringResource(R.string.app_name)) {
        Icon(
          painterResource(R.drawable.logo),
          modifier = Modifier.size(20.dp),
          contentDescription = null,
          tint = Color.Unspecified,
        )
      }
      BasicText(
        text = title,
        maxLines = 1,
        color = { titleColor },
        style = MaterialTheme.typography.titleMedium,
        autoSize =
          TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 16.sp, stepSize = 1.sp),
      )
    }
    if (subtitle.isNotEmpty()) {
      Text(
        subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
      )
    }
  }
}

@Composable
private fun LeadingAction(action: AppBarAction?) {
  when (action?.actionType) {
    AppBarActionType.NAVIGATE_UP ->
      IconButton(onClick = action.actionFn) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
          contentDescription = stringResource(R.string.cd_navigate_back_icon),
        )
      }
    AppBarActionType.MENU ->
      IconButton(onClick = action.actionFn) {
        Icon(imageVector = Icons.Rounded.Menu, contentDescription = stringResource(R.string.cd_menu))
      }
    else -> Unit
  }
}

@Composable
private fun TrailingAction(action: AppBarAction?) {
  when (action?.actionType) {
    AppBarActionType.APP_SETTING ->
      IconButton(onClick = action.actionFn) {
        Icon(
          imageVector = Icons.Rounded.Settings,
          contentDescription = stringResource(R.string.cd_app_settings_icon),
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }
    AppBarActionType.NAVIGATE_UP -> TextButton(onClick = action.actionFn) { Text("Done") }
    else -> Unit
  }
}
