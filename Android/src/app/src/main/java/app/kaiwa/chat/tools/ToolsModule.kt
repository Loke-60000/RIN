// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat.tools

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import app.kaiwa.proto.McpServers
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Resolves [PermissionGate] to the UI-backed [UiPermissionGate] so tool calls prompt the user. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ToolsModule {
  @Binds @Singleton abstract fun bindPermissionGate(gate: UiPermissionGate): PermissionGate

  companion object {
    @Provides
    @Singleton
    fun provideMcpServersDataStore(@ApplicationContext context: Context): DataStore<McpServers> =
      DataStoreFactory.create(
        serializer = McpServersSerializer,
        produceFile = { context.dataStoreFile("mcp_servers.pb") },
      )
  }
}
