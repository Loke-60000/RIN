// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.runtime

import android.content.Context
import android.graphics.Bitmap
import app.kaiwa.data.Model
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CoroutineScope

typealias ResultListener =
  (partialResult: String, done: Boolean, partialThinkingResult: String?) -> Unit

typealias CleanUpListener = () -> Unit

/**
 * Base interface for all LLM runtimes. It defines the foundational operations needed to initialize,
 * manage conversations, execute inferences, and clean up resources for different Large Language
 * Model backends.
 */
interface LlmModelHelper {
  /**
   * Initializes the LLM runtime with the specified configuration.
   *
   * @param context the application context.
   * @param model the model to be initialized.
   * @param taskId the task id where the model is being used.
   * @param supportImage whether to support image input.
   * @param supportAudio whether to support audio input.
   * @param onDone callback invoked when initialization is completed successfully.
   * @param systemInstruction instruction provided to guide the model's behavior.
   * @param tools tools available for the model to use.
   * @param enableConversationConstrainedDecoding whether to enable constrained decoding for
   *   conversations.
   * @param coroutineScope optional coroutine scope for async execution.
   */
  fun initialize(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    enableConversationConstrainedDecoding: Boolean = false,
    coroutineScope: CoroutineScope? = null,
  )

  /**
   * Resets the conversation context for the specified model.
   *
   * @param model the model whose conversation context needs to be reset.
   * @param supportImage whether to preserve support for image input.
   * @param supportAudio whether to preserve support for audio input.
   * @param systemInstruction new system instruction to guide the model's behavior after reset.
   * @param tools new or updated tools available for the model.
   * @param enableConversationConstrainedDecoding whether to enable constrained decoding.
   */
  fun resetConversation(
    model: Model,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    enableConversationConstrainedDecoding: Boolean = false,
    initialMessages: List<Message> = listOf(),
  )

  /**
   * Cleans up resources occupied by the model.
   *
   * @param model the model whose resources should be cleaned up.
   * @param onDone callback invoked when clean up completes.
   */
  fun cleanUp(model: Model, onDone: () -> Unit)

  /**
   * Runs an inference pass on the specified model.
   *
   * @param model the model to run inference on.
   * @param input the input text for inference.
   * @param resultListener callback invoked with partial inference results.
   * @param cleanUpListener callback invoked to trigger necessary cleanup.
   * @param onError callback invoked if an error occurs during inference.
   * @param images optional list of images provided as input context.
   * @param audioClips optional list of audio clips provided as input context.
   * @param coroutineScope optional coroutine scope for async inference execution.
   * @param extraContext optional extra context for inference.
   */
  fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit = {},
    images: List<Bitmap> = listOf(),
    audioClips: List<ByteArray> = listOf(),
    coroutineScope: CoroutineScope? = null,
    extraContext: Map<String, String>? = null,
  )

  /**
   * Stops the ongoing response generation for the model.
   *
   * @param model the ongoing model response to be stopped.
   */
  fun stopResponse(model: Model)
}
