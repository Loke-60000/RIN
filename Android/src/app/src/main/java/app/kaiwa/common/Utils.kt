// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.exifinterface.media.ExifInterface
import app.kaiwa.GalleryEvent
import app.kaiwa.data.SAMPLE_RATE
import app.kaiwa.firebaseAnalytics
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "AGUtils"

const val LOCAL_URL_BASE = "https://appassets.androidplatform.net"

/** Drops the MediaPipe stack-trace tail that some task errors append after their message. */
fun cleanUpMediapipeTaskErrorMessage(message: String): String {
  val traceMarker = message.indexOf("=== Source Location Trace")
  return if (traceMarker < 0) message else message.substring(0, traceMarker)
}

fun processLlmResponse(response: String): String = response.replace("\\n", "\n")

/** Fetches [url] over HTTP GET and decodes the body into [T], keeping the raw text alongside it. */
inline fun <reified T> getJsonResponse(url: String): JsonObjAndTextContent<T>? {
  try {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
    connection.connect()

    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
      Log.e("AGUtils", "HTTP error: ${connection.responseCode}")
      return null
    }

    val body = connection.inputStream.bufferedReader().use { it.readText() }
    val parsed = parseJson<T>(body) ?: return null
    return JsonObjAndTextContent(jsonObj = parsed, textContent = body)
  } catch (e: Exception) {
    Log.e("AGUtils", "Error when getting or parsing json response", e)
    return null
  }
}

/** Parses a JSON string into an object of type [T] using Gson. */
inline fun <reified T> parseJson(response: String): T? {
  return try {
    Gson().fromJson(response, T::class.java)
  } catch (e: Exception) {
    Log.e("AGUtils", "Error parsing JSON string", e)
    null
  }
}

/**
 * Reads the WAV file at [stereoUri], normalizes it to 16-bit mono at [SAMPLE_RATE], trims it to
 * [maxSeconds], and returns the result as an [AudioClip], or null if the input is unusable.
 */
fun convertWavToMonoWithMaxSeconds(
  context: Context,
  stereoUri: Uri,
  maxSeconds: Int = 30,
): AudioClip? {
  Log.d(TAG, "Start to convert wav file to mono channel")

  try {
    val rawBytes = openUri(context, stereoUri)?.use { it.readBytes() } ?: return null

    // A canonical WAV header is 44 bytes; anything shorter cannot be decoded.
    if (rawBytes.size < 44) {
      Log.e(TAG, "Not a valid wav file")
      return null
    }

    val header = ByteBuffer.wrap(rawBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
    val channels = header.getShort(22)
    var sampleRate = header.getInt(24)
    val bitDepth = header.getShort(34)
    Log.d(TAG, "File metadata: channels: $channels, sampleRate: $sampleRate, bitDepth: $bitDepth")

    val payload = rawBytes.copyOfRange(44, rawBytes.size)
    val normalized16Bit =
      if (bitDepth.toInt() == 8) {
        Log.d(TAG, "Converting 8-bit audio to 16-bit.")
        convert8BitTo16Bit(payload)
      } else {
        payload
      }

    val sampleBuffer =
      ByteBuffer.wrap(normalized16Bit).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var samples = ShortArray(sampleBuffer.remaining())
    sampleBuffer.get(samples)

    if (sampleRate < SAMPLE_RATE) {
      Log.d(TAG, "Resampling from $sampleRate Hz to $SAMPLE_RATE Hz.")
      samples = resample(samples, sampleRate, SAMPLE_RATE, channels.toInt())
      sampleRate = SAMPLE_RATE
      Log.d(TAG, "Resampling complete. New sample count: ${samples.size}")
    }

    var monoSamples =
      if (channels.toInt() == 2) {
        Log.d(TAG, "Converting stereo to mono.")
        downmixStereoToMono(samples)
      } else {
        Log.d(TAG, "Audio is already mono. No channel conversion needed.")
        samples
      }

    val sampleCap = maxSeconds * sampleRate
    if (monoSamples.size > sampleCap) {
      Log.d(TAG, "Trimming clip from ${monoSamples.size} samples to $sampleCap samples.")
      monoSamples = monoSamples.copyOfRange(0, sampleCap)
    }

    val out = ByteBuffer.allocate(monoSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    out.asShortBuffer().put(monoSamples)
    return AudioClip(audioData = out.array(), sampleRate = sampleRate)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to convert wav to mono", e)
    return null
  }
}

private fun downmixStereoToMono(interleaved: ShortArray): ShortArray {
  val mono = ShortArray(interleaved.size / 2)
  for (i in mono.indices) {
    val left = interleaved[i * 2]
    val right = interleaved[i * 2 + 1]
    mono[i] = ((left + right) / 2).toShort()
  }
  return mono
}

/** Expands unsigned 8-bit PCM samples into signed little-endian 16-bit samples. */
private fun convert8BitTo16Bit(eightBit: ByteArray): ByteArray {
  val sixteenBit = ByteArray(eightBit.size * 2)
  val buffer = ByteBuffer.wrap(sixteenBit).order(ByteOrder.LITTLE_ENDIAN)
  for (b in eightBit) {
    // Unsigned byte (0..255) -> center on zero (-128..127) -> scale up into the 16-bit range.
    val unsigned = b.toInt() and 0xFF
    buffer.putShort(((unsigned - 128) * 256).toShort())
  }
  return sixteenBit
}

/** Linearly interpolates mono PCM from [originalSampleRate] to [targetSampleRate]. */
private fun resample(
  inputSamples: ShortArray,
  originalSampleRate: Int,
  targetSampleRate: Int,
  channels: Int,
): ShortArray {
  if (originalSampleRate == targetSampleRate) return inputSamples

  val ratio = targetSampleRate.toDouble() / originalSampleRate
  val resampled = ShortArray((inputSamples.size * ratio).toInt())

  if (channels == 1) {
    for (i in resampled.indices) {
      val position = i / ratio
      val lowerIndex = floor(position).toInt()
      val upperIndex = lowerIndex + 1
      val fraction = position - lowerIndex

      val lower = if (lowerIndex < inputSamples.size) inputSamples[lowerIndex].toDouble() else 0.0
      val upper = if (upperIndex < inputSamples.size) inputSamples[upperIndex].toDouble() else 0.0
      resampled[i] = (lower * (1 - fraction) + upper * fraction).toInt().toShort()
    }
  }

  return resampled
}

fun calculatePeakAmplitude(buffer: ByteArray, bytesRead: Int): Int {
  val samples =
    ByteBuffer.wrap(buffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
  var peak = 0
  while (samples.hasRemaining()) {
    val magnitude = abs(samples.get().toInt())
    if (magnitude > peak) peak = magnitude
  }
  return peak
}

fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
  // First pass: read only the bounds so we can pick a subsampling factor.
  val options =
    BitmapFactory.Options().apply {
      inJustDecodeBounds = true
      openUri(context, uri)?.use { BitmapFactory.decodeStream(it, null, this) }
      inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
      inJustDecodeBounds = false
    }

  // Second pass: actually decode at the chosen sample size.
  return openUri(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

/** Opens [uri] as a stream, treating scheme-less and `file://` URIs as plain filesystem paths. */
private fun openUri(context: Context, uri: Uri): java.io.InputStream? =
  if (uri.scheme == null || uri.scheme == "file") {
    FileInputStream(uri.path ?: "")
  } else {
    context.contentResolver.openInputStream(uri)
  }

fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
  val matrix = Matrix()
  when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1.0f, 1.0f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1.0f, -1.0f)
    ExifInterface.ORIENTATION_TRANSPOSE -> {
      matrix.postRotate(90f)
      matrix.preScale(-1.0f, 1.0f)
    }
    ExifInterface.ORIENTATION_TRANSVERSE -> {
      matrix.postRotate(270f)
      matrix.preScale(-1.0f, 1.0f)
    }
    ExifInterface.ORIENTATION_NORMAL -> return bitmap
    else -> return bitmap
  }
  return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun calculateInSampleSize(
  options: BitmapFactory.Options,
  reqWidth: Int,
  reqHeight: Int,
): Int {
  val height = options.outHeight
  val width = options.outWidth
  if (height <= reqHeight && width <= reqWidth) return 1

  // Use the larger ratio so both dimensions end up at or below the requested size.
  val heightRatio = (height.toFloat() / reqHeight.toFloat()).roundToInt()
  val widthRatio = (width.toFloat() / reqWidth.toFloat()).roundToInt()
  return max(heightRatio, widthRatio)
}

fun readFileToByteBuffer(file: File): ByteBuffer? {
  return try {
    FileInputStream(file).use { stream ->
      val channel: FileChannel = stream.channel
      val buffer = ByteBuffer.allocateDirect(channel.size().toInt())
      channel.read(buffer)
      buffer.rewind()
      buffer
    }
  } catch (e: Exception) {
    e.printStackTrace()
    null
  }
}

private fun modelContains(token: String): Boolean =
  Build.MODEL?.lowercase()?.contains(token) == true

fun isPixel10(): Boolean = modelContains("pixel 10")

fun isPixelDevice(): Boolean = modelContains("pixel")

/** Clears focus from the focused field once the IME has shown and then been dismissed. */
fun Modifier.clearFocusOnKeyboardDismiss(): Modifier = composed {
  var isFocused by remember { mutableStateOf(false) }
  var keyboardAppearedSinceLastFocused by remember { mutableStateOf(false) }

  if (isFocused) {
    val imeIsVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current

    LaunchedEffect(imeIsVisible) {
      if (imeIsVisible) {
        keyboardAppearedSinceLastFocused = true
      } else if (keyboardAppearedSinceLastFocused) {
        focusManager.clearFocus()
      }
    }
  }

  onFocusEvent {
    if (isFocused != it.isFocused) {
      isFocused = it.isFocused
      if (isFocused) keyboardAppearedSinceLastFocused = false
    }
  }
}

fun isAICoreSupported(allowedDeviceModels: Set<String>?): Boolean {
  if (allowedDeviceModels.isNullOrEmpty()) {
    Log.w(TAG, "isAICoreSupported: allowedDeviceModels is null or empty")
    return false
  }

  val currentModel = android.os.Build.MODEL?.lowercase()
  if (currentModel == null) {
    Log.w(TAG, "isAICoreSupported: current device model is null")
    return false
  }

  val supported = allowedDeviceModels.contains(currentModel)
  if (!supported) {
    Log.w(
      TAG,
      "isAICoreSupported: device model '$currentModel' is not in the allowed list: $allowedDeviceModels",
    )
  }
  return supported
}

fun logErrorToFirebase(event: GalleryEvent, errorType: String, errorMessage: String?) {
  firebaseAnalytics?.logEvent(
    event.id,
    Bundle().apply {
      putBoolean("success", false)
      putString("error_type", errorType)
      putString("error_message", errorMessage ?: "Unknown error")
    },
  )
}

fun convertStringToJsonObject(jsonString: String): JsonObject {
  return try {
    JsonParser.parseString(jsonString).asJsonObject
  } catch (e: Exception) {
    JsonObject()
  }
}
