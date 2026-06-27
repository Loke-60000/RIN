// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Adds depth and an occasional glossy reflection to a logo without changing its colours: a soft
 * top-to-bottom shade gives it dimension, and a bright highlight sweeps diagonally across once each
 * cycle, then rests. Everything is composited with [BlendMode.SrcAtop] so it only touches the
 * logo's own pixels and the artwork's grey/red stay intact.
 */
@Composable
fun Modifier.logoSheen(): Modifier {
  val transition = rememberInfiniteTransition(label = "sheen")
  val sweep by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(tween(durationMillis = 4200, easing = FastOutSlowInEasing), RepeatMode.Restart),
      label = "sweep",
    )
  return this.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
      // Color-preserving shading: a little light off the top, a little shadow into the bottom.
      val shade =
        Brush.verticalGradient(
          0f to Color.White.copy(alpha = 0.22f),
          0.45f to Color.Transparent,
          1f to Color.Black.copy(alpha = 0.16f),
        )
      onDrawWithContent {
        drawContent()
        drawRect(shade, blendMode = BlendMode.SrcAtop)
        // The reflection only fires during the first stretch of each cycle, then the logo rests.
        if (sweep < REFLECT_FRACTION) {
          val p = sweep / REFLECT_FRACTION
          val band = size.width * 0.45f
          val cx = -band + p * (size.width + 2f * band)
          val streak =
            Brush.linearGradient(
              colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.6f), Color.Transparent),
              start = Offset(cx - band, 0f),
              end = Offset(cx + band, size.height),
            )
          drawRect(streak, blendMode = BlendMode.SrcAtop)
        }
      }
    }
}

private const val REFLECT_FRACTION = 0.42f
