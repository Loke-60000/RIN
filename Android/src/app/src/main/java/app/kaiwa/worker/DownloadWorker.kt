// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.kaiwa.data.KEY_MODEL_COMMIT_HASH
import app.kaiwa.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import app.kaiwa.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import app.kaiwa.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import app.kaiwa.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import app.kaiwa.data.KEY_MODEL_DOWNLOAD_RATE
import app.kaiwa.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import app.kaiwa.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import app.kaiwa.data.KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES
import app.kaiwa.data.KEY_MODEL_EXTRA_DATA_URLS
import app.kaiwa.data.KEY_MODEL_IS_IMPORTED
import app.kaiwa.data.KEY_MODEL_IS_ZIP
import app.kaiwa.data.KEY_MODEL_NAME
import app.kaiwa.data.KEY_MODEL_START_UNZIPPING
import app.kaiwa.data.KEY_MODEL_TOTAL_BYTES
import app.kaiwa.data.KEY_MODEL_UNZIPPED_DIR
import app.kaiwa.data.KEY_MODEL_URL
import app.kaiwa.data.TMP_FILE_EXT
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadWorker"

private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "model_download_channel_foreground"

// How often (ms) to push progress to WorkManager.
private const val PROGRESS_INTERVAL_MS = 200L

// Number of recent samples averaged for the download-rate estimate.
private const val RATE_WINDOW = 5

// Guard so the notification channel is registered only once per process.
private var channelCreated = false

data class UrlAndFileName(val url: String, val fileName: String)

/**
 * Downloads (and optionally unzips) a model's files in the background.
 *
 * Files are fetched sequentially. A partially-written ".$TMP_FILE_EXT" file from a prior attempt is
 * resumed via an HTTP Range request; once complete it is renamed to the final file name. Progress
 * and a foreground notification are updated roughly every [PROGRESS_INTERVAL_MS].
 */
class DownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  private val externalFilesDir = context.getExternalFilesDir(null)

  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private val notificationId: Int = params.id.hashCode()

  init {
    if (!channelCreated) {
      val channel =
        NotificationChannel(
            FOREGROUND_NOTIFICATION_CHANNEL_ID,
            "Model Downloading",
            // Silent: progress shouldn't buzz the device.
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply { description = "Notifications for model downloading" }
      notificationManager.createNotificationChannel(channel)
      channelCreated = true
    }
  }

  override suspend fun doWork(): Result {
    val fileUrl = inputData.getString(KEY_MODEL_URL)
    val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
    val version = inputData.getString(KEY_MODEL_COMMIT_HASH)!!
    val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME)
    val modelDir = inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR)!!
    val isModelImported = inputData.getBoolean(KEY_MODEL_IS_IMPORTED, false)
    val isZip = inputData.getBoolean(KEY_MODEL_IS_ZIP, false)
    val unzippedDir = inputData.getString(KEY_MODEL_UNZIPPED_DIR)
    val extraDataFileUrls = inputData.getString(KEY_MODEL_EXTRA_DATA_URLS)?.split(",") ?: listOf()
    val extraDataFileNames =
      inputData.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES)?.split(",") ?: listOf()
    val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
    val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)

    if (fileUrl == null || fileName == null) {
      return Result.failure()
    }

    return withContext(Dispatchers.IO) {
      try {
        setForeground(createForegroundInfo(progress = 0, modelName = modelName))

        val allFiles = buildList {
          add(UrlAndFileName(url = fileUrl, fileName = fileName))
          for (index in extraDataFileUrls.indices) {
            add(UrlAndFileName(url = extraDataFileUrls[index], fileName = extraDataFileNames[index]))
          }
        }
        Log.d(TAG, "About to download: $allFiles")

        // TODO: maybe consider downloading them in parallel.
        var downloadedBytes = 0L
        val sizeSamples = ArrayDeque<Long>()
        val latencySamples = ArrayDeque<Long>()

        for (file in allFiles) {
          downloadedBytes =
            downloadOne(
              file = file,
              accessToken = accessToken,
              isModelImported = isModelImported,
              modelDir = modelDir,
              version = version,
              totalBytes = totalBytes,
              modelName = modelName,
              downloadedBytes = downloadedBytes,
              sizeSamples = sizeSamples,
              latencySamples = latencySamples,
            )

          if (isZip && unzippedDir != null) {
            unzip(modelDir = modelDir, version = version, fileName = fileName, unzippedDir = unzippedDir)
          }
        }
        Result.success()
      } catch (e: IOException) {
        Log.e(TAG, e.message, e)
        Result.failure(Data.Builder().putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message).build())
      }
    }
  }

  /** Downloads a single file (resuming if a temp file exists) and returns the running byte total. */
  private suspend fun downloadOne(
    file: UrlAndFileName,
    accessToken: String?,
    isModelImported: Boolean,
    modelDir: String,
    version: String,
    totalBytes: Long,
    modelName: String,
    downloadedBytes: Long,
    sizeSamples: ArrayDeque<Long>,
    latencySamples: ArrayDeque<Long>,
  ): Long {
    var runningBytes = downloadedBytes
    val connection = (URL(file.url).openConnection() as HttpURLConnection)
    if (accessToken != null) {
      Log.d(TAG, "Using access token: ${accessToken.subSequence(0, 10)}...")
      connection.setRequestProperty("Authorization", "Bearer $accessToken")
    }

    val outputDir =
      if (isModelImported) {
        File(applicationContext.getExternalFilesDir(null), modelDir)
      } else {
        File(
          applicationContext.getExternalFilesDir(null),
          listOf(modelDir, version).joinToString(File.separator),
        )
      }
    if (!outputDir.exists()) {
      outputDir.mkdirs()
    }

    val outputTmpFile =
      if (isModelImported) {
        File(
          applicationContext.getExternalFilesDir(null),
          listOf(modelDir, "${file.fileName}.$TMP_FILE_EXT").joinToString(File.separator),
        )
      } else {
        File(
          applicationContext.getExternalFilesDir(null),
          listOf(modelDir, version, "${file.fileName}.$TMP_FILE_EXT").joinToString(File.separator),
        )
      }

    val existingBytes = outputTmpFile.length()
    if (existingBytes > 0) {
      Log.d(
        TAG,
        "File '${outputTmpFile.name}' partial size: $existingBytes. Trying to resume download",
      )
      connection.setRequestProperty("Range", "bytes=$existingBytes-")
      // Disable compression so the resumed byte offsets stay meaningful.
      connection.setRequestProperty("Accept-Encoding", "identity")
    }

    connection.connect()
    Log.d(TAG, "response code: ${connection.responseCode}")

    if (
      connection.responseCode != HttpURLConnection.HTTP_OK &&
        connection.responseCode != HttpURLConnection.HTTP_PARTIAL
    ) {
      throw IOException("HTTP error code: ${connection.responseCode}")
    }

    val contentRange = connection.getHeaderField("Content-Range")
    if (contentRange != null) {
      val byteRange = contentRange.substringAfter("bytes ").split("/")[0].split("-")
      val startByte = byteRange[0].toLong()
      val endByte = byteRange[1].toLong()
      Log.d(TAG, "Content-Range: $contentRange. Start bytes: $startByte, end bytes: $endByte")
      runningBytes += startByte
    } else {
      Log.d(TAG, "Download starts from beginning.")
    }

    connection.inputStream.use { input ->
      FileOutputStream(outputTmpFile, /* append= */ true).use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var lastProgressTs = 0L
        var deltaBytes = 0L
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
          output.write(buffer, 0, bytesRead)
          runningBytes += bytesRead
          deltaBytes += bytesRead

          val now = System.currentTimeMillis()
          if (now - lastProgressTs > PROGRESS_INTERVAL_MS) {
            var bytesPerMs = 0f
            if (lastProgressTs != 0L) {
              if (sizeSamples.size == RATE_WINDOW) sizeSamples.removeFirst()
              sizeSamples.addLast(deltaBytes)
              if (latencySamples.size == RATE_WINDOW) latencySamples.removeFirst()
              latencySamples.addLast(now - lastProgressTs)
              deltaBytes = 0L
              bytesPerMs = sizeSamples.sum().toFloat() / latencySamples.sum()
            }

            val remainingMs =
              if (bytesPerMs > 0f && totalBytes > 0L) (totalBytes - runningBytes) / bytesPerMs
              else 0f

            setProgress(
              Data.Builder()
                .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, runningBytes)
                .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs.toLong())
                .build()
            )
            setForeground(
              createForegroundInfo(
                progress = (runningBytes * 100 / totalBytes).toInt(),
                modelName = modelName,
              )
            )
            Log.d(TAG, "downloadedBytes: $runningBytes")
            lastProgressTs = now
          }
        }
      }
    }

    // Drop the temp suffix to finalize the file.
    val finalFile = File(outputTmpFile.absolutePath.replace(".$TMP_FILE_EXT", ""))
    if (finalFile.exists()) {
      finalFile.delete()
    }
    outputTmpFile.renameTo(finalFile)
    Log.d(TAG, "Download done")

    return runningBytes
  }

  /** Extracts a downloaded zip into its destination dir and deletes the archive. */
  private suspend fun unzip(
    modelDir: String,
    version: String,
    fileName: String,
    unzippedDir: String,
  ) {
    setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())

    val destDir =
      File(externalFilesDir, listOf(modelDir, version, unzippedDir).joinToString(File.separator))
    if (!destDir.exists()) {
      destDir.mkdirs()
    }

    val zipFilePath =
      listOf(externalFilesDir.toString(), modelDir, version, fileName).joinToString(File.separator)
    val buffer = ByteArray(4096)

    ZipInputStream(BufferedInputStream(FileInputStream(zipFilePath))).use { zipIn ->
      var entry: ZipEntry? = zipIn.nextEntry
      while (entry != null) {
        val outPath = destDir.absolutePath + File.separator + entry.name
        if (entry.isDirectory) {
          File(outPath).mkdirs()
        } else {
          FileOutputStream(outPath).use { out ->
            var len: Int
            while (zipIn.read(buffer).also { len = it } > 0) {
              out.write(buffer, 0, len)
            }
          }
        }
        zipIn.closeEntry()
        entry = zipIn.nextEntry
      }
    }

    File(zipFilePath).delete()
  }

  override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(0)

  /**
   * Builds the ongoing foreground notification that keeps the worker alive and tells the user a
   * download is in progress.
   */
  private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
    val title = if (modelName != null) "Downloading \"$modelName\"" else "Downloading model"
    val content = "Downloading in progress: $progress%"

    val intent =
      Intent(applicationContext, Class.forName("app.kaiwa.MainActivity")).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        applicationContext,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val notification =
      NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .setProgress(100, progress, false)
        .setContentIntent(pendingIntent)
        .build()

    return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  }
}
