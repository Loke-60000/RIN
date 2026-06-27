// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

private const val TAG = "AGTtsVoice"

/**
 * Fully on-device, GMS-free text-to-speech (sherpa-onnx + Piper voices). Voices are downloaded on
 * demand to internal storage; each is self-contained (model + tokens + espeak-ng-data), so synthesis
 * runs entirely offline — a real replacement for the system [android.speech.tts.TextToSpeech], which
 * doesn't exist on de-Googled ROMs (GrapheneOS).
 *
 * All blocking work ([download], [speak]) must run off the main thread. Playback streams float PCM
 * straight to an [AudioTrack] so [stop] can interrupt mid-utterance.
 */
@Singleton
class TtsVoiceManager @Inject constructor(@ApplicationContext context: Context) {
  private val appContext = context.applicationContext
  private val root = File(appContext.filesDir, "tts")

  /** False on ABIs we don't ship the native lib for (only arm64-v8a); callers then hide TTS UI. */
  private val libOk: Boolean =
    try {
      System.loadLibrary("sherpa-onnx-jni")
      true
    } catch (t: Throwable) {
      Log.w(TAG, "sherpa-onnx native lib unavailable on this ABI", t)
      false
    }

  @Volatile private var tts: OfflineTts? = null
  @Volatile private var loadedLocale: String? = null

  private val playLock = Any()
  @Volatile private var track: AudioTrack? = null
  @Volatile private var stopped = false

  private fun voiceDir(locale: String): File = File(root, locale)

  private fun onnxIn(dir: File): File? =
    dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.firstOrNull()

  fun isInstalled(locale: String): Boolean {
    val dir = voiceDir(locale)
    return onnxIn(dir) != null && File(dir, "tokens.txt").exists()
  }

  /** Locales with a fully-extracted voice on disk. */
  fun installedLocales(): List<String> =
    root.listFiles()?.filter { it.isDirectory && isInstalled(it.name) }?.map { it.name }?.sorted()
      ?: emptyList()

  /** True when on-device TTS can actually run here (native lib present + at least one voice). */
  fun isAvailable(): Boolean = libOk && installedLocales().isNotEmpty()

  fun supported(): Boolean = libOk

  /**
   * Downloads and extracts [voice] into internal storage. Blocking — call from a background
   * dispatcher. [onProgress] receives 0f..1f as bytes arrive (best-effort; may stay at 0 if the
   * server omits Content-Length). Throws on failure, leaving no partial voice behind.
   */
  fun download(voice: TtsVoice, onProgress: (Float) -> Unit) {
    val dir = voiceDir(voice.locale)
    val tmp = File(root, "${voice.locale}.download")
    root.mkdirs()
    tmp.deleteRecursively()
    dir.deleteRecursively()
    try {
      val conn = URL(voice.url).openConnection()
      conn.connect()
      val total = conn.contentLengthLong
      val archive = File(root, "${voice.locale}.tar.bz2")
      conn.getInputStream().use { input ->
        archive.outputStream().use { out ->
          val buf = ByteArray(1 shl 16)
          var read: Int
          var got = 0L
          while (input.read(buf).also { read = it } >= 0) {
            out.write(buf, 0, read)
            got += read
            if (total > 0) onProgress((got.toFloat() / total).coerceIn(0f, 1f))
          }
        }
      }
      extract(archive, tmp)
      archive.delete()
      // The tarball nests everything under a single top folder; promote its contents to dir/.
      val top = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: tmp
      if (!top.renameTo(dir)) {
        top.copyRecursively(dir, overwrite = true)
      }
      tmp.deleteRecursively()
      if (!isInstalled(voice.locale)) error("voice incomplete after extract")
      Log.i(TAG, "Installed voice ${voice.locale}")
    } catch (t: Throwable) {
      tmp.deleteRecursively()
      dir.deleteRecursively()
      throw t
    }
  }

  fun delete(locale: String) {
    if (loadedLocale == locale) release()
    voiceDir(locale).deleteRecursively()
  }

  /** Removes every installed voice. */
  fun deleteAll() {
    release()
    root.deleteRecursively()
  }

  /** Total bytes all installed voices occupy on disk. */
  fun sizeBytes(): Long =
    if (root.exists()) root.walk().filter { it.isFile }.sumOf { it.length() } else 0L

  /** Loads (or reuses) the engine for [locale]. Blocking. Returns false if it can't be loaded. */
  @Synchronized
  fun load(locale: String): Boolean {
    if (!libOk) return false
    if (loadedLocale == locale && tts != null) return true
    val dir = voiceDir(locale)
    val onnx = onnxIn(dir) ?: return false
    val tokens = File(dir, "tokens.txt")
    if (!tokens.exists()) return false
    val espeak = File(dir, "espeak-ng-data")
    return try {
      val vits =
        OfflineTtsVitsModelConfig(
          model = onnx.absolutePath,
          tokens = tokens.absolutePath,
          dataDir = if (espeak.isDirectory) espeak.absolutePath else "",
        )
      val config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = 2))
      tts?.release()
      tts = OfflineTts(config = config)
      loadedLocale = locale
      true
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to load voice $locale", t)
      tts = null
      loadedLocale = null
      false
    }
  }

  /**
   * Synthesizes [text] with the [locale] voice and plays it, interrupting anything already playing.
   * Blocking — call from a background dispatcher.
   */
  fun speak(text: String, locale: String) {
    val clean = stripMarkdown(text)
    if (clean.isEmpty()) return
    if (!load(locale)) return
    val engine = tts ?: return
    stop()
    stopped = false
    val audio =
      try {
        engine.generate(text = clean, sid = 0, speed = 1.0f)
      } catch (t: Throwable) {
        Log.e(TAG, "Synthesis failed", t)
        return
      }
    play(audio.samples, audio.sampleRate)
  }

  private fun play(samples: FloatArray, sampleRate: Int) {
    if (samples.isEmpty()) return
    val minBuf =
      AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_FLOAT,
      )
    val bufBytes = maxOf(minBuf, samples.size * 4).coerceAtLeast(4096)
    val t =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(bufBytes)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    synchronized(playLock) { track = t }
    t.play()
    // Write in chunks so a concurrent stop() takes effect promptly.
    var offset = 0
    val chunk = 4096
    while (offset < samples.size && !stopped) {
      val n = minOf(chunk, samples.size - offset)
      val written = t.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
      if (written < 0) break
      offset += written
    }
    synchronized(playLock) {
      if (track === t) {
        runCatching {
          if (!stopped) {
            // Let the device drain the last buffer before tearing down.
            Thread.sleep((1000L * (samples.size - offset).coerceAtLeast(0) / sampleRate))
          }
          t.stop()
        }
        t.release()
        track = null
      } else {
        runCatching { t.release() }
      }
    }
  }

  /** Interrupts playback immediately. Safe to call from any thread. */
  fun stop() {
    stopped = true
    synchronized(playLock) {
      track?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.stop() }; runCatching { it.release() } }
      track = null
    }
  }

  fun release() {
    stop()
    synchronized(this) {
      tts?.release()
      tts = null
      loadedLocale = null
    }
  }

  private fun extract(archive: File, destDir: File) {
    destDir.mkdirs()
    val destPath = destDir.canonicalPath
    // bzip2 issues many small reads; a large source buffer keeps it from starving on syscalls.
    val source = BufferedInputStream(archive.inputStream(), 1 shl 20)
    BZip2CompressorInputStream(source).use { bz ->
      TarArchiveInputStream(bz).use { tar ->
        var entry = tar.nextEntry
        while (entry != null) {
          val out = File(destDir, entry.name)
          // Path-traversal guard.
          if (out.canonicalPath.startsWith(destPath)) {
            if (entry.isDirectory) {
              out.mkdirs()
            } else {
              out.parentFile?.mkdirs()
              out.outputStream().buffered(1 shl 18).use { tar.copyTo(it, 1 shl 18) }
            }
          }
          entry = tar.nextEntry
        }
      }
    }
  }
}

/** Strips the most common Markdown noise so the voice reads clean prose. */
private fun stripMarkdown(md: String): String =
  md
    .replace(Regex("```[\\s\\S]*?```"), " code block ")
    .replace(Regex("`([^`]*)`"), "$1")
    .replace(Regex("(?m)^#{1,6}\\s*"), "")
    .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
    .replace(Regex("\\*([^*]+)\\*"), "$1")
    .replace(Regex("(?m)^\\s*[-*]\\s+"), "")
    .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
    .trim()
