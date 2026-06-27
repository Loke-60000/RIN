// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.chat.tools

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the user chose for a pending tool-call confirmation. */
enum class PermissionDecision {
  ALLOW_ONCE,
  ALWAYS_ALLOW,
  DENY,
}

/**
 * A pending MCP tool-call confirmation. The model's tool-calling thread parks on [deferred] until the
 * UI resolves it; the UI reads [toolName]/[argument] to render the dialog.
 */
class PermissionRequest(
  val toolName: String,
  val argument: String,
  private val deferred: CompletableDeferred<PermissionDecision>,
) {
  fun resolve(decision: PermissionDecision) {
    deferred.complete(decision)
  }
}

/**
 * A [PermissionGate] backed by a UI dialog. [confirmMcpToolCall] runs on the model's tool-calling
 * thread (off the main thread): it publishes a [PermissionRequest] and suspends on a
 * [CompletableDeferred] until the dialog resolves it, so the gate never blocks the UI thread and the
 * native call simply waits for the user's answer.
 *
 * "Always allow" is handled by the caller (it flips the tool's `alwaysAllow` flag); here it just maps
 * to allowing the current call.
 */
@Singleton
class UiPermissionGate @Inject constructor() : PermissionGate {
  private val _pending = MutableStateFlow<PermissionRequest?>(null)
  val pending = _pending.asStateFlow()

  override suspend fun confirmMcpToolCall(toolName: String, argument: String): Boolean {
    val deferred = CompletableDeferred<PermissionDecision>()
    val request = PermissionRequest(toolName, argument, deferred)
    _pending.update { request }
    val decision =
      try {
        deferred.await()
      } finally {
        // Clear only if this request is still the one on screen (a newer one mustn't be wiped).
        _pending.update { if (it === request) null else it }
      }
    return decision != PermissionDecision.DENY
  }
}
