// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import java.util.Random

/**
 * A subtle risograph / film-grain overlay drawn over the content: a tiled monochrome noise veil
 * plus a soft vignette. It adds tactile depth and a printed, cinematic finish without touching any
 * of the UI below it. Keep [alpha] low (0.03–0.08) so it reads as texture, never as static.
 */
/** Deep risograph-ink indigo used to tint the vignette — adds printed depth without a flat black. */
private val RisoInk = Color(0xFF11173A)

@Composable
fun Modifier.filmGrain(
  alpha: Float = 0.075f,
  vignette: Float = 0.13f,
): Modifier {
  val grain = remember { grainBrush() }
  return this.drawWithContent {
    drawContent()
    // Fine grain veil — visible on light and dark surfaces alike (neutral noise, normal blend).
    drawRect(brush = grain, alpha = alpha)
    // Soft riso-ink vignette concentrates attention and adds printed depth at the edges.
    if (vignette > 0f) {
      val radius = maxOf(size.width, size.height) * 0.78f
      drawRect(
        brush =
          Brush.radialGradient(
            colorStops = arrayOf(0.58f to Color.Transparent, 1f to RisoInk.copy(alpha = vignette)),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = radius,
          )
      )
    }
  }
}

/** A repeating monochrome-noise brush (a small noise tile sampled with REPEAT) for the grain. */
private fun grainBrush(tile: Int = 140): ShaderBrush {
  val pixels = IntArray(tile * tile)
  val rnd = Random(7)
  for (i in pixels.indices) {
    // Centre the noise around mid-grey so the veil lightens and darkens evenly (true film grain).
    val v = (110 + rnd.nextInt(96)).coerceIn(0, 255)
    pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
  }
  val bmp = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
  bmp.setPixels(pixels, 0, tile, 0, 0, tile, tile)
  return ShaderBrush(BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
}
