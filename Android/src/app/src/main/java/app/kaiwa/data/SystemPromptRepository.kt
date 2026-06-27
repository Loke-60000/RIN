// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

import androidx.datastore.core.DataStore
import app.kaiwa.proto.UserData
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists per-task user-defined system prompts.
 *
 * Prompts are stored in the [UserData] proto's secrets map under a task-derived key. Reads return
 * only the raw saved value; callers that need a default fallback should go through
 * [app.kaiwa.common.SystemPromptHelper.getEffectiveSystemPrompt] instead.
 */
@Singleton
open class SystemPromptRepository
@Inject
constructor(private val userDataDataStore: DataStore<UserData>) {

  /** Stores [newPrompt] as the custom system prompt for [taskId]. */
  suspend fun updateSystemPrompt(taskId: String, newPrompt: String) {
    userDataDataStore.updateData { it.toBuilder().putSecrets(keyFor(taskId), newPrompt).build() }
  }

  /** Emits the custom system prompt saved for [taskId], or null when none is set. */
  fun getCustomSystemPrompt(taskId: String): Flow<String?> =
    userDataDataStore.data.map { it.secretsMap[keyFor(taskId)] }

  /** Removes any custom system prompt previously saved for [taskId]. */
  suspend fun clearCustomSystemPrompt(taskId: String) {
    userDataDataStore.updateData { userData ->
      val key = keyFor(taskId)
      if (userData.secretsMap.containsKey(key)) {
        userData.toBuilder().removeSecrets(key).build()
      } else {
        userData
      }
    }
  }

  private fun keyFor(taskId: String): String = "system_prompt_$taskId"
}
