// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa

import android.app.Application
import app.kaiwa.data.DataStoreRepository
import app.kaiwa.notifications.NotificationScheduleManager
import app.kaiwa.ui.theme.ThemeSettings
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject lateinit var notificationScheduleManager: NotificationScheduleManager

  override fun onCreate() {
    super.onCreate()
    // Rehydrate any scheduled download notifications persisted from a previous run.
    notificationScheduleManager.initialize()
    // Apply the user's saved theme preference.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()
  }
}
