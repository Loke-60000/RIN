// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.customtasks.common

import android.content.Context
import androidx.compose.runtime.Composable
import app.kaiwa.data.Model
import app.kaiwa.data.Task
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.CoroutineScope

/**
 * A pluggable task that the app can surface on its home screen.
 *
 * Each task advertises its [task] metadata (label, description, icon, and the models it can run);
 * those models appear on the task's detail screen so the user can pick one and run it. A task
 * supplies the lifecycle hooks for its models ([initializeModelFn] / [cleanUpModelFn]) and the
 * [MainScreen] content rendered inside the app-provided Scaffold.
 *
 * To add a task: implement this interface, populate [task], implement the two lifecycle hooks and
 * [MainScreen], then contribute the implementation into the multibound `Set<CustomTask>` from a Hilt
 * module via `@Provides` + `@IntoSet`. See the implementations under `app.kaiwa.customtasks` for
 * working examples.
 */
interface CustomTask {
  /** Metadata describing this task and its models. */
  val task: Task

  /**
   * Prepares [model] for use, optionally with a [systemInstruction]. Invoked on a coroutine using
   * the default dispatcher. Report completion through [onDone]: an empty string means success, any
   * other string is an error message.
   */
  fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (error: String) -> Unit,
  )

  /** Releases resources held by [model], signaling completion via [onDone]. */
  fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  )

  /** Renders the task's detail UI. [data] is typically a [CustomTaskData]. */
  @Composable fun MainScreen(data: Any)
}

/**
 * Declares the multibound `Set<CustomTask>` so it can always be injected, even when no module
 * contributes an entry. Without this declaration the set would be a missing binding once every
 * contributing module is removed.
 */
@Module
@InstallIn(SingletonComponent::class)
interface CustomTaskModule {
  @Multibinds fun customTasks(): Set<CustomTask>
}
