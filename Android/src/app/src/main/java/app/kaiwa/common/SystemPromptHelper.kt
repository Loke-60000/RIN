// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.common

import app.kaiwa.data.SystemPromptRepository
import app.kaiwa.data.Task
import kotlinx.coroutines.flow.firstOrNull

/** Resolves which system prompt a task should actually run with. */
object SystemPromptHelper {

  /**
   * Returns the system prompt to use for [task]: the user's saved override from [repo] when one
   * exists, otherwise the task's built-in default. A null [repo] always yields the default.
   */
  suspend fun getEffectiveSystemPrompt(repo: SystemPromptRepository?, task: Task): String {
    val override = repo?.getCustomSystemPrompt(task.id)?.firstOrNull()
    return override ?: task.defaultSystemPrompt
  }
}
