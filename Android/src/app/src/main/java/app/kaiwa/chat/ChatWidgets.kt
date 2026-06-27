// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaiwa.R
import app.kaiwa.i18n.tr
import app.kaiwa.ui.common.logoSheen

/** The brand gradient (send button, cursor, avatar) — derived from the active theme's accent. */
@Composable
internal fun gemmaBrush(): Brush {
  val c = MaterialTheme.colorScheme
  return Brush.linearGradient(listOf(c.primary, lerp(c.primary, c.secondary, 0.5f), c.secondary))
}

/** The Gemma logo mark. */
@Composable
internal fun GemmaMark(size: Dp, modifier: Modifier = Modifier) {
  ComposeImage(
    painter = painterResource(R.drawable.gemma_logo),
    contentDescription = null,
    modifier = modifier.size(size).logoSheen(),
  )
}

/** A gently pulsing Gemma logo, used as the loading indicator. */
@Composable
internal fun AnimatedGemmaMark(size: Dp, modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "gemma")
  val scale by
    transition.animateFloat(
      initialValue = 0.8f,
      targetValue = 1.15f,
      animationSpec =
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "scale",
    )
  val alpha by
    transition.animateFloat(
      initialValue = 0.5f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "alpha",
    )
  ComposeImage(
    painter = painterResource(R.drawable.gemma_logo),
    contentDescription = null,
    modifier =
      modifier
        .size(size)
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
          this.alpha = alpha
        },
  )
}

@Composable
internal fun EmptyState(phase: ChatPhase) {
  Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (phase == ChatPhase.INITIALIZING) AnimatedGemmaMark(56.dp) else GemmaMark(56.dp)
      Text(
        if (phase == ChatPhase.INITIALIZING) tr("chat.waking_model", "Waking up the model…") else tr("chat.where_start", "Where should we start?"),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
internal fun ModelError(modelName: String, message: String, onNavigateToModels: () -> Unit) {
  Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      GemmaMark(56.dp)
      Text(
        if (modelName.isNotBlank()) "$modelName ${tr("chat.model_load_failed_suffix_short", "couldn't load")}" else tr("chat.model_load_failed", "This model couldn't load"),
        style = MaterialTheme.typography.titleMedium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      Text(
        message.ifBlank { tr("chat.model_load_failed_hint", "It may be incompatible with this device.") } +
          tr("chat.model_load_failed_suffix", "\n\nPick another model from the menu at the top, or add a cloud model in Settings."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      TextButton(onClick = onNavigateToModels) { Text(tr("model.manage", "Download / manage models")) }
    }
  }
}

@Composable
internal fun NoModel(message: String, onNavigateToModels: () -> Unit) {
  Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      GemmaMark(56.dp)
      Text(tr("chat.no_model_title", "No model yet"), style = MaterialTheme.typography.titleMedium)
      Text(
        message.ifBlank { tr("chat.no_model_desc", "Download a Gemma model, or pick a cloud model, to start chatting.") },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      TextButton(onClick = onNavigateToModels) { Text(tr("model.manage", "Download / manage models")) }
    }
  }
}

/** Top-bar nav icon that morphs (fade + scale) between a hamburger and a back arrow. */
@Composable
internal fun AnimatedNavIcon(isBack: Boolean, onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    AnimatedContent(
      targetState = isBack,
      transitionSpec = {
        (fadeIn(tween(220)) + scaleIn(initialScale = 0.6f)) togetherWith
          (fadeOut(tween(140)) + scaleOut(targetScale = 0.6f))
      },
      label = "navIcon",
    ) { back ->
      Icon(
        if (back) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Menu,
        contentDescription = if (back) tr("common.back", "Back") else tr("common.menu", "Menu"),
      )
    }
  }
}
