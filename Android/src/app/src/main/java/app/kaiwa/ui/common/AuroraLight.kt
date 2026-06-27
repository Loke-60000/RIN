// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.common

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

// Soft "northern-lights" wash that can glow from either edge: light centres drift and wave just past
// the screen edge, their glow tinted between the two accent shades and faded toward the middle. The
// bottom glow greets the empty/welcome screen; the top glow rides in while the model generates.
private const val AURORA_AGSL =
  """
  uniform float2 res;
  uniform float time;
  uniform float bottomI;
  uniform float topI;
  layout(color) uniform half4 c1;
  layout(color) uniform half4 c2;

  // One soft, waving glow rising from an edge. [dist] is 0 at that edge and 1 at the far side.
  float edgeGlow(float dist, float x, float reach, float t) {
    float boundary = reach + 0.10 * sin(x * 2.3 + t * 0.5) + 0.05 * sin(x * 4.6 - t * 0.33);
    float vfade = smoothstep(boundary, 0.0, dist);
    float ripple = 0.70 + 0.30 * sin(x * 3.2 + t * 0.55);
    return vfade * vfade * ripple;  // squared fade = extra-soft blend into the background
  }

  half4 main(float2 frag) {
    float2 uv = frag / res;
    float t = time;

    // Bottom glow reaches ~two thirds up; the top glow stays shallower so it doesn't wash behind text.
    float light = edgeGlow(1.0 - uv.y, uv.x, 0.62, t) * bottomI
                + edgeGlow(uv.y, uv.x, 0.42, t + 40.0) * topI;

    float mixx = 0.5 + 0.5 * sin(uv.x * 2.2 + t * 0.4);
    half3 tint = mix(c1.rgb, c2.rgb, mixx);

    float al = clamp(light, 0.0, 1.0);
    return half4(tint * al, al);
  }
  """

/**
 * Draws a flowing, gently waving accent light behind content (after filling [surface]). Uses an AGSL
 * runtime shader on Android 13+; older devices get a soft drifting radial glow. The glow can rise
 * from the bottom edge ([bottomIntensity], for the empty/welcome screen) and/or descend from the top
 * edge ([topIntensity], while the model generates a reply); each is a 0..1 fade the caller drives.
 */
@Composable
fun Modifier.accentAuroraLight(
  surface: Color,
  primary: Color,
  secondary: Color,
  bottomIntensity: Float,
  topIntensity: Float,
): Modifier {
  val transition = rememberInfiniteTransition(label = "aurora")
  val time by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 120f,
      animationSpec = infiniteRepeatable(tween(120_000, easing = LinearEasing), RepeatMode.Restart),
      label = "auroraTime",
    )

  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    val shader = remember { RuntimeShader(AURORA_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    drawWithCache {
      onDrawBehind {
        drawRect(surface)
        if (bottomIntensity > 0.001f || topIntensity > 0.001f) {
          shader.setFloatUniform("res", size.width, size.height)
          shader.setFloatUniform("time", time)
          shader.setFloatUniform("bottomI", bottomIntensity)
          shader.setFloatUniform("topI", topIntensity)
          shader.setColorUniform("c1", primary.toArgb())
          shader.setColorUniform("c2", secondary.toArgb())
          drawRect(brush)
        }
      }
    }
  } else {
    // Pre-13 fallback: a soft radial glow at whichever edge is active, drifting with [time].
    drawBehind {
      drawRect(surface)
      val drift = kotlin.math.sin(time.toDouble()).toFloat()
      if (bottomIntensity > 0.001f) {
        drawRect(
          Brush.radialGradient(
            listOf(primary.copy(alpha = 0.55f * bottomIntensity), Color.Transparent),
            center = Offset(size.width * (0.5f + 0.12f * drift), size.height * 0.98f),
            radius = size.maxDimension * 0.62f,
          )
        )
      }
      if (topIntensity > 0.001f) {
        drawRect(
          Brush.radialGradient(
            listOf(primary.copy(alpha = 0.55f * topIntensity), Color.Transparent),
            center = Offset(size.width * (0.5f - 0.12f * drift), size.height * 0.02f),
            radius = size.maxDimension * 0.5f,
          )
        )
      }
    }
  }
}
