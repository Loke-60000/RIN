// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import app.kaiwa.AppLifecycleProvider
import app.kaiwa.BenchmarkResultsSerializer
import app.kaiwa.CutoutsSerializer
import app.kaiwa.GalleryLifecycleProvider
import app.kaiwa.SettingsSerializer
import app.kaiwa.SkillsSerializer
import app.kaiwa.UserDataSerializer
import app.kaiwa.data.DataStoreRepository
import app.kaiwa.data.DefaultDataStoreRepository
import app.kaiwa.data.DefaultDownloadRepository
import app.kaiwa.data.DownloadRepository
import app.kaiwa.proto.BenchmarkResults
import app.kaiwa.proto.CutoutCollection
import app.kaiwa.proto.Settings
import app.kaiwa.proto.Skills
import app.kaiwa.proto.UserData
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Application-wide Hilt wiring.
 *
 * Each proto type gets its own singleton [Serializer] plus a backing [DataStore] persisted to a
 * dedicated `*.pb` file. The repositories that the rest of the app injects are assembled here on top
 * of those stores.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {

  private const val FILE_SETTINGS = "settings.pb"
  private const val FILE_CUTOUTS = "cutouts.pb"
  private const val FILE_USER_DATA = "user_data.pb"
  private const val FILE_BENCHMARK_RESULTS = "benchmark_results.pb"
  private const val FILE_SKILLS = "skills.pb"

  /** Builds a single-type [DataStore] backed by [fileName] under the app's data-store directory. */
  private fun <T> buildStore(
    context: Context,
    serializer: Serializer<T>,
    fileName: String,
  ): DataStore<T> =
    DataStoreFactory.create(serializer = serializer) { context.dataStoreFile(fileName) }

  @Provides @Singleton fun provideSettingsSerializer(): Serializer<Settings> = SettingsSerializer

  @Provides
  @Singleton
  fun provideCutoutSerializer(): Serializer<CutoutCollection> = CutoutsSerializer

  @Provides @Singleton fun provideUserDataSerializer(): Serializer<UserData> = UserDataSerializer

  @Provides
  @Singleton
  fun provideBenchmarkResultsSerializer(): Serializer<BenchmarkResults> = BenchmarkResultsSerializer

  @Provides @Singleton fun provideSkillsSerializer(): Serializer<Skills> = SkillsSerializer

  @Provides
  @Singleton
  fun provideSettingsDataStore(
    @ApplicationContext context: Context,
    settingsSerializer: Serializer<Settings>,
  ): DataStore<Settings> = buildStore(context, settingsSerializer, FILE_SETTINGS)

  @Provides
  @Singleton
  fun provideCutoutsDataStore(
    @ApplicationContext context: Context,
    cutoutsSerializer: Serializer<CutoutCollection>,
  ): DataStore<CutoutCollection> = buildStore(context, cutoutsSerializer, FILE_CUTOUTS)

  @Provides
  @Singleton
  fun provideUserDataDataStore(
    @ApplicationContext context: Context,
    userDataSerializer: Serializer<UserData>,
  ): DataStore<UserData> = buildStore(context, userDataSerializer, FILE_USER_DATA)

  @Provides
  @Singleton
  fun provideBenchmarkResultsDataStore(
    @ApplicationContext context: Context,
    benchmarkResultsSerializer: Serializer<BenchmarkResults>,
  ): DataStore<BenchmarkResults> =
    buildStore(context, benchmarkResultsSerializer, FILE_BENCHMARK_RESULTS)

  @Provides
  @Singleton
  fun provideSkillsDataStore(
    @ApplicationContext context: Context,
    skillsSerializer: Serializer<Skills>,
  ): DataStore<Skills> = buildStore(context, skillsSerializer, FILE_SKILLS)

  @Provides
  @Singleton
  fun provideAppLifecycleProvider(): AppLifecycleProvider = GalleryLifecycleProvider()

  @Provides
  @Singleton
  fun provideDataStoreRepository(
    dataStore: DataStore<Settings>,
    userDataDataStore: DataStore<UserData>,
    cutoutsDataStore: DataStore<CutoutCollection>,
    benchmarkResultsStore: DataStore<BenchmarkResults>,
    skillsDataStore: DataStore<Skills>,
  ): DataStoreRepository =
    DefaultDataStoreRepository(
      dataStore,
      userDataDataStore,
      cutoutsDataStore,
      benchmarkResultsStore,
      skillsDataStore,
    )

  @Provides
  @Singleton
  fun provideDownloadRepository(
    @ApplicationContext context: Context,
    lifecycleProvider: AppLifecycleProvider,
  ): DownloadRepository = DefaultDownloadRepository(context, lifecycleProvider)

  @Provides @Singleton fun provideMoshi(): Moshi = Moshi.Builder().build()
}
