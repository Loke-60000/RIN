// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import app.kaiwa.data.SAMPLE_RATE
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGChatAudioRecorder"
private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

/**
 * Records microphone audio in-app as raw 16-bit mono PCM at [SAMPLE_RATE] — the format the Gemma
 * audio backend expects. Caller must hold RECORD_AUDIO permission before calling [start].
 */
class ChatAudioRecorder {
  private var recorder: AudioRecord? = null
  private val stream = ByteArrayOutputStream()
  @Volatile private var recording = false

  val isRecording: Boolean
    get() = recording

  @SuppressLint("MissingPermission")
  fun start(scope: CoroutineScope, onLevel: ((Float) -> Unit)? = null) {
    if (recording) return
    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
    val rec =
      AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, minBuf)
    recorder = rec
    stream.reset()
    recording = true
    try {
      rec.startRecording()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start recording", e)
      recording = false
      rec.release()
      recorder = null
      return
    }
    scope.launch(Dispatchers.IO) {
      val buffer = ByteArray(minBuf)
      while (recording) {
        val n = rec.read(buffer, 0, buffer.size)
        if (n > 0) {
          stream.write(buffer, 0, n)
          if (onLevel != null) {
            val level = rmsLevel(buffer, n)
            withContext(Dispatchers.Main) { onLevel(level) }
          }
        }
      }
    }
  }

  /** RMS amplitude of a little-endian 16-bit PCM chunk, normalised to roughly 0..1 for speech. */
  private fun rmsLevel(buffer: ByteArray, length: Int): Float {
    var sum = 0.0
    var i = 0
    val samples = length / 2
    while (i + 1 < length) {
      val lo = buffer[i].toInt() and 0xFF
      val hi = buffer[i + 1].toInt() // signed → preserves sample sign
      val sample = (hi shl 8) or lo
      sum += sample.toDouble() * sample.toDouble()
      i += 2
    }
    if (samples == 0) return 0f
    val rms = Math.sqrt(sum / samples)
    // Speech RMS sits in the low thousands; ~6000 maps a normal voice toward full scale.
    return (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
  }

  /**
   * Stops recording and returns the clip as a self-contained 16-bit mono WAV (empty if nothing was
   * recorded). LiteRT-LM decodes audio via miniaudio, which needs a real container header — raw PCM
   * fails with "Failed to initialize miniaudio decoder".
   */
  fun stop(): ByteArray {
    if (!recording) return ByteArray(0)
    recording = false
    recorder?.let { r ->
      try {
        if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
      } catch (e: Exception) {
        Log.w(TAG, "stop() failed", e)
      }
      r.release()
    }
    recorder = null
    val pcm = stream.toByteArray()
    stream.reset()
    return if (pcm.isEmpty()) ByteArray(0) else pcmToWav(pcm, SAMPLE_RATE, channels = 1, bitsPerSample = 16)
  }

  /** Prepends a 44-byte RIFF/WAVE header so the raw PCM becomes a decodable WAV file. */
  private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataLen = pcm.size
    val header =
      ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + dataLen)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16) // PCM fmt chunk size
        putShort(1) // audioFormat = PCM
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort(blockAlign.toShort())
        putShort(bitsPerSample.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataLen)
      }
    return header.array() + pcm
  }
}
