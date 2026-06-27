// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.common

import android.Manifest
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.kaiwa.data.Model
import app.kaiwa.data.Task
import app.kaiwa.ui.modelmanager.ModelManagerViewModel
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "AGUtils"

val SMALL_BUTTON_CONTENT_PADDING =
  PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)

/** Format the bytes into a human-readable format. */
fun Long.humanReadableSize(si: Boolean = true, extraDecimalForGbAndAbove: Boolean = false): String {
  val base = if (si) 1000 else 1024
  if (this < base) return "$this B"

  val magnitude = (ln(this.toDouble()) / ln(base.toDouble())).toInt()
  val prefix = (if (si) "kMGTPE" else "KMGTPE")[magnitude - 1] + (if (si) "" else "i")
  val useExtraDecimal = extraDecimalForGbAndAbove && prefix.lowercase() != "k" && prefix != "M"
  val pattern = if (useExtraDecimal) "%.2f %sB" else "%.1f %sB"
  return pattern.format(this / base.toDouble().pow(magnitude.toDouble()), prefix)
}

fun Float.humanReadableDuration(): String {
  val milliseconds = this
  if (milliseconds < 1000) return "$milliseconds ms"

  val seconds = milliseconds / 1000f
  if (seconds < 60) return "%.1f s".format(seconds)

  val minutes = seconds / 60f
  if (minutes < 60) return "%.1f min".format(minutes)

  return "%.1f h".format(minutes / 60f)
}

fun Long.formatToHourMinSecond(): String {
  if (this < 0) return "-"

  val totalSeconds = this / 1000
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60

  val parts = mutableListOf<String>()
  if (hours > 0) parts.add("$hours h")
  if (minutes > 0) parts.add("$minutes min")
  if (seconds > 0 || (hours == 0L && minutes == 0L)) parts.add("$seconds sec")
  return parts.joinToString(" ")
}

private val DISTINCTIVE_COLORS =
  listOf(
    Color(0xff3cb44b),
    Color(0xffffe119),
    Color(0xff4363d8),
    Color(0xfff58231),
    Color(0xff911eb4),
    Color(0xff46f0f0),
    Color(0xfff032e6),
    Color(0xffbcf60c),
    Color(0xfffabebe),
    Color(0xff008080),
    Color(0xffe6beff),
    Color(0xff9a6324),
    Color(0xfffffac8),
    Color(0xff800000),
    Color(0xffaaffc3),
    Color(0xff808000),
    Color(0xffffd8b1),
    Color(0xff000075),
  )

fun getDistinctiveColor(index: Int): Color = DISTINCTIVE_COLORS[index % DISTINCTIVE_COLORS.size]

fun Context.createTempPictureUri(
  fileName: String = "picture_${System.currentTimeMillis()}",
  fileExtension: String = ".png",
): Uri {
  val tempFile = File.createTempFile(fileName, fileExtension, cacheDir).apply { createNewFile() }
  return FileProvider.getUriForFile(
    applicationContext,
    "app.kaiwa.provider" /* {applicationId}.provider */,
    tempFile,
  )
}

fun checkNotificationPermissionAndStartDownload(
  context: Context,
  launcher: ManagedActivityResultLauncher<String, Boolean>,
  modelManagerViewModel: ModelManagerViewModel,
  task: Task?,
  model: Model,
) {
  val granted =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
  if (granted) {
    modelManagerViewModel.downloadModel(task = task, model = model)
  } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
  }
}

fun ensureValidFileName(fileName: String): String =
  fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")

/**
 * Animates text "swiping" into view from left to right.
 *
 * The reveal is driven by a moving linear-gradient brush applied to the text color, paired with an
 * alpha fade so the glyphs become visible as the gradient sweeps across them.
 */
@Composable
fun SwipingText(
  text: String,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier,
  animationDelay: Long = 0,
  animationDurationMs: Int = 300,
  edgeGradientRelativeSize: Float = 1.0f,
) {
  val progress =
    rememberDelayedAnimationProgress(
      initialDelay = animationDelay,
      animationDurationMs = animationDurationMs,
      animationLabel = "swiping text",
      easing = LinearEasing,
    )
  val sweep = (1f + edgeGradientRelativeSize) * progress
  Text(
    text,
    style =
      style.copy(
        brush =
          linearGradient(
            colorStops =
              arrayOf(
                sweep - edgeGradientRelativeSize to color,
                sweep to Color.Transparent,
              )
          )
      ),
    modifier = modifier.graphicsLayer { alpha = progress },
  )
}

/**
 * Animates text "wiping" into view from left to right using a gradient mask.
 *
 * The content is rendered first, then a moving linear-gradient rectangle is composited with
 * [BlendMode.DstOut]: wherever the mask is opaque it punches the destination text out, so as the
 * mask slides its transparent edge progressively exposes the text. Offscreen compositing is
 * required for the blend to operate against the text rather than the whole layer.
 */
@Composable
fun RevealingText(
  text: String,
  style: TextStyle,
  modifier: Modifier = Modifier,
  annotatedText: AnnotatedString? = null,
  animationDelay: Long = 0,
  animationDurationMs: Int = 300,
  edgeGradientRelativeSize: Float = 0.5f,
  extraTextPadding: Dp = 16.dp,
) {
  val progress =
    rememberDelayedAnimationProgress(
      initialDelay = animationDelay,
      animationDurationMs = animationDurationMs,
      animationLabel = "revealing text",
    )
  val maskBrush = buildRevealMask(progress, edgeGradientRelativeSize)
  Box(
    modifier = modifier.revealMaskLayer(maskBrush),
    contentAlignment = Alignment.Center,
  ) {
    val textModifier = Modifier.padding(horizontal = extraTextPadding)
    if (annotatedText != null) {
      Text(annotatedText, style = style, modifier = textModifier)
    } else {
      Text(text, style = style, modifier = textModifier)
    }
  }
}

/** Another version of RevealingText with animationProgress passed in. */
@Composable
fun RevealingText(
  text: String,
  style: TextStyle,
  animationProgress: Float,
  modifier: Modifier = Modifier,
  textAlign: TextAlign? = null,
  edgeGradientRelativeSize: Float = 0.5f,
) {
  val maskBrush = buildRevealMask(animationProgress, edgeGradientRelativeSize)
  Box(
    modifier = modifier.revealMaskLayer(maskBrush),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text,
      style = style,
      modifier = modifier.padding(horizontal = 16.dp),
      textAlign = textAlign,
    )
  }
}

private fun buildRevealMask(progress: Float, edgeGradientRelativeSize: Float) =
  linearGradient(
    colorStops =
      arrayOf(
        (1f + edgeGradientRelativeSize) * progress - edgeGradientRelativeSize to Color.Transparent,
        (1f + edgeGradientRelativeSize) * progress to Color.Red,
      )
  )

private fun Modifier.revealMaskLayer(maskBrush: androidx.compose.ui.graphics.Brush): Modifier =
  this.graphicsLayer(alpha = 0.99f, compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
      drawContent()
      drawRect(brush = maskBrush, blendMode = BlendMode.DstOut)
    }

/**
 * Provides an animated float that ramps from 0f to 1f after [initialDelay], handy for staggered
 * "enter" animations.
 */
@Composable
fun rememberDelayedAnimationProgress(
  initialDelay: Long = 0,
  animationDurationMs: Int,
  animationLabel: String,
  easing: Easing = FastOutSlowInEasing,
): Float {
  var startAnimation by remember { mutableStateOf(false) }
  val progress: Float by
    animateFloatAsState(
      if (startAnimation) 1f else 0f,
      label = animationLabel,
      animationSpec = tween(durationMillis = animationDurationMs, easing = easing),
    )
  LaunchedEffect(Unit) {
    delay(initialDelay)
    startAnimation = true
  }
  return progress
}

private fun Bitmap.writePngTo(file: File) {
  FileOutputStream(file).use { compress(Bitmap.CompressFormat.PNG, 100, it) }
}

private fun Context.cacheImageFile(fileName: String): File {
  val imagesDir = File(cacheDir, "images").apply { mkdirs() }
  return File(imagesDir, fileName)
}

suspend fun Context.shareBitmap(
  bitmap: Bitmap,
  fileName: String = "shared_image.png",
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  withContext(dispatcher) {
    try {
      val tempFile = cacheImageFile(fileName)
      bitmap.writePngTo(tempFile)
      val contentUri =
        FileProvider.getUriForFile(this@shareBitmap, "$packageName.provider", tempFile)
      val shareIntent =
        Intent().apply {
          action = Intent.ACTION_SEND
          putExtra(Intent.EXTRA_STREAM, contentUri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          type = "image/png"
          clipData = ClipData.newRawUri("", contentUri)
        }
      startActivity(Intent.createChooser(shareIntent, "Share Image"))
    } catch (e: Exception) {
      Log.e(TAG, "Failed to share bitmap", e)
    }
  }
}

suspend fun Context.copyBitmapToClipboard(
  bitmap: Bitmap,
  fileName: String = "copied_image.png",
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  withContext(dispatcher) {
    try {
      val tempFile = cacheImageFile(fileName)
      bitmap.writePngTo(tempFile)
      val contentUri =
        FileProvider.getUriForFile(this@copyBitmapToClipboard, "$packageName.provider", tempFile)
      val clipboard =
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
      clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "Image", contentUri))
    } catch (e: Exception) {
      Log.e(TAG, "Failed to copy bitmap to clipboard", e)
    }
  }
}

suspend fun Context.saveBitmapToMediaStore(
  bitmap: Bitmap,
  fileName: String,
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean {
  return withContext(dispatcher) {
    val resolver: ContentResolver = contentResolver
    val imageCollection: Uri =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
      } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
      }

    val now = System.currentTimeMillis()
    val contentValues =
      ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.DATE_ADDED, now / 1000)
        put(MediaStore.Images.Media.DATE_TAKEN, now)
      }

    var imageUri: Uri? = null
    try {
      imageUri = resolver.insert(imageCollection, contentValues) ?: return@withContext false
      val success =
        resolver.openOutputStream(imageUri)?.use { outputStream ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        } ?: false

      if (!success) {
        resolver.delete(imageUri, null, null)
      }
      success
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save bitmap to MediaStore", e)
      imageUri?.let { resolver.delete(it, null, null) }
      false
    }
  }
}
