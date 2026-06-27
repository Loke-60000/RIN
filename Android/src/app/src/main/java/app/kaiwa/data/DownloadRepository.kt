// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.kaiwa.AppLifecycleProvider
import app.kaiwa.GalleryEvent
import app.kaiwa.R
import app.kaiwa.firebaseAnalytics
import app.kaiwa.worker.DownloadWorker
import java.util.UUID
import java.util.concurrent.Executors

private const val TAG = "AGDownloadRepository"
private const val MODEL_NAME_TAG = "modelName"
private const val TASK_ID_TAG = "taskId"

// Sentinel task id used when a download is started from the global model manager.
private const val DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID = "___"

private const val DOWNLOAD_CHANNEL_ID = "download_notification"
private const val DOWNLOAD_CHANNEL_NAME = "AI Edge Gallery download notification"

data class AGWorkInfo(val taskId: String, val modelName: String, val workId: String)

interface DownloadRepository {
  fun downloadModel(
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )

  fun cancelDownloadModel(model: Model)

  fun cancelAll(onComplete: () -> Unit)

  fun observerWorkerProgress(
    workerId: UUID,
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )
}

/**
 * WorkManager-backed implementation of [DownloadRepository].
 *
 * Each download runs in a unique [DownloadWorker] keyed by model name; this class enqueues the
 * work, relays the worker's progress into [ModelDownloadStatus] callbacks, and posts completion
 * notifications (deep-linking back into the app) while logging analytics events.
 */
class DefaultDownloadRepository(
  private val context: Context,
  private val lifecycleProvider: AppLifecycleProvider,
) : DownloadRepository {
  private val workManager = WorkManager.getInstance(context)

  // Persisted (across app restarts) start time per model name, for duration analytics.
  private val downloadStartTimeSharedPreferences =
    context.getSharedPreferences("download_start_time_ms", Context.MODE_PRIVATE)

  override fun downloadModel(
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    val totalBytes = model.totalBytes + model.extraDataFiles.sumOf { it.sizeInBytes }

    val inputDataBuilder =
      Data.Builder()
        .putString(KEY_MODEL_NAME, model.name)
        .putString(KEY_MODEL_URL, model.url)
        .putString(KEY_MODEL_COMMIT_HASH, model.version)
        .putString(
          KEY_MODEL_DOWNLOAD_MODEL_DIR,
          if (model.imported) IMPORTS_DIR else model.normalizedName,
        )
        .putString(KEY_MODEL_DOWNLOAD_FILE_NAME, model.downloadFileName)
        .putBoolean(KEY_MODEL_IS_ZIP, model.isZip)
        .putString(KEY_MODEL_UNZIPPED_DIR, model.unzipDir)
        .putLong(KEY_MODEL_TOTAL_BYTES, totalBytes)
        .putBoolean(KEY_MODEL_IS_IMPORTED, model.imported)

    if (model.extraDataFiles.isNotEmpty()) {
      inputDataBuilder
        .putString(KEY_MODEL_EXTRA_DATA_URLS, model.extraDataFiles.joinToString(",") { it.url })
        .putString(
          KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES,
          model.extraDataFiles.joinToString(",") { it.downloadFileName },
        )
    }
    model.accessToken?.let { inputDataBuilder.putString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, it) }

    val request =
      OneTimeWorkRequestBuilder<DownloadWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(inputDataBuilder.build())
        .addTag("$MODEL_NAME_TAG:${model.name}")
        .addTag("$TASK_ID_TAG:${task?.id ?: ""}")
        .build()

    workManager.enqueueUniqueWork(model.name, ExistingWorkPolicy.REPLACE, request)

    observerWorkerProgress(
      workerId = request.id,
      task = task,
      model = model,
      onStatusUpdated = onStatusUpdated,
    )
  }

  override fun cancelDownloadModel(model: Model) {
    workManager.cancelAllWorkByTag("$MODEL_NAME_TAG:${model.name}")
  }

  override fun cancelAll(onComplete: () -> Unit) {
    workManager.cancelAllWork().result.addListener(
      { onComplete() },
      Executors.newSingleThreadExecutor(),
    )
  }

  override fun observerWorkerProgress(
    workerId: UUID,
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    workManager.getWorkInfoByIdLiveData(workerId).observeForever { workInfo ->
      workInfo ?: return@observeForever
      when (workInfo.state) {
        WorkInfo.State.ENQUEUED -> onEnqueued(model)
        WorkInfo.State.RUNNING -> onRunning(workInfo, model, onStatusUpdated)
        WorkInfo.State.SUCCEEDED -> onSucceeded(workerId, task, model, onStatusUpdated)
        WorkInfo.State.FAILED,
        WorkInfo.State.CANCELLED -> onFailedOrCancelled(workInfo, workerId, model, onStatusUpdated)
        else -> {}
      }
    }
  }

  private fun onEnqueued(model: Model) {
    downloadStartTimeSharedPreferences.edit { putLong(model.name, System.currentTimeMillis()) }
    firebaseAnalytics?.logEvent(
      GalleryEvent.MODEL_DOWNLOAD.id,
      bundleOf("event_type" to "start", "model_id" to model.name),
    )
  }

  private fun onRunning(
    workInfo: WorkInfo,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    val progress = workInfo.progress
    if (progress.getBoolean(KEY_MODEL_START_UNZIPPING, false)) {
      onStatusUpdated(model, ModelDownloadStatus(status = ModelDownloadStatusType.UNZIPPING))
      return
    }

    val receivedBytes = progress.getLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, 0L)
    if (receivedBytes == 0L) {
      return
    }
    onStatusUpdated(
      model,
      ModelDownloadStatus(
        status = ModelDownloadStatusType.IN_PROGRESS,
        totalBytes = model.totalBytes,
        receivedBytes = receivedBytes,
        bytesPerSecond = progress.getLong(KEY_MODEL_DOWNLOAD_RATE, 0L),
        remainingMs = progress.getLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, 0L),
      ),
    )
  }

  private fun onSucceeded(
    workerId: UUID,
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    Log.d("repo", "worker %s success".format(workerId.toString()))
    onStatusUpdated(model, ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED))
    sendNotification(
      title = context.getString(R.string.notification_title_success),
      text = context.getString(R.string.notification_content_success).format(model.name),
      taskId = task?.id ?: DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID,
      modelName = model.name,
    )
    logDownloadOutcome("success", model)
  }

  private fun onFailedOrCancelled(
    workInfo: WorkInfo,
    workerId: UUID,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    val errorMessage = workInfo.outputData.getString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE) ?: ""
    Log.d("repo", "worker %s FAILED or CANCELLED: %s".format(workerId.toString(), errorMessage))

    val status =
      if (workInfo.state == WorkInfo.State.CANCELLED) {
        ModelDownloadStatusType.NOT_DOWNLOADED
      } else {
        sendNotification(
          title = context.getString(R.string.notification_title_fail),
          text = context.getString(R.string.notification_content_success).format(model.name),
          taskId = "",
          modelName = "",
        )
        ModelDownloadStatusType.FAILED
      }
    onStatusUpdated(model, ModelDownloadStatus(status = status, errorMessage = errorMessage))
    // TODO: Add failure reasons
    logDownloadOutcome("failure", model)
  }

  private fun logDownloadOutcome(eventType: String, model: Model) {
    val startTime = downloadStartTimeSharedPreferences.getLong(model.name, 0L)
    val duration = System.currentTimeMillis() - startTime
    firebaseAnalytics?.logEvent(
      GalleryEvent.MODEL_DOWNLOAD.id,
      bundleOf("event_type" to eventType, "model_id" to model.name, "duration_ms" to duration),
    )
    downloadStartTimeSharedPreferences.edit { remove(model.name) }
  }

  private fun sendNotification(title: String, text: String, taskId: String, modelName: String) {
    // Notifications are only useful when the app is backgrounded.
    if (lifecycleProvider.isAppInForeground) {
      return
    }

    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(
      NotificationChannel(
        DOWNLOAD_CHANNEL_ID,
        DOWNLOAD_CHANNEL_NAME,
        NotificationManager.IMPORTANCE_HIGH,
      )
    )

    val intent =
      when {
        // Failed download: just relaunch the app.
        taskId.isEmpty() ->
          context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        // Started from the global model manager: open that screen.
        taskId == DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID ->
          Intent(Intent.ACTION_VIEW, "app.kaiwa://global_model_manager".toUri())
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        // Otherwise deep-link into the relevant model page.
        else ->
          Intent(Intent.ACTION_VIEW, "app.kaiwa://model/$taskId/$modelName".toUri())
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
      }

    val pendingIntent =
      PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification =
      NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
        // TODO: replace icon.
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    with(NotificationManagerCompat.from(context)) {
      if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
      ) {
        return
      }
      notify(1, notification)
    }
  }
}
