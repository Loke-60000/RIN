// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.kaiwa.MainActivity
import app.kaiwa.chat.ChatPrefs
import app.kaiwa.ui.modelmanager.ModelManagerViewModel
import app.kaiwa.ui.theme.GalleryTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The translucent popup that hosts the Gemma assistant UI. Launched by [OkGemmaSession] when the
 * assistant is summoned (e.g. long-press power button), or directly for testing.
 */
@AndroidEntryPoint
class AssistantOverlayActivity : ComponentActivity() {

  private val modelManagerViewModel: ModelManagerViewModel by viewModels()
  private val assistantViewModel: AssistantViewModel by viewModels()

  // This activity is singleInstance, so a lingering instance is reused on the next summon and
  // onCreate won't re-run. Recreate it so every summon gets a fresh, freshly-prepared popup.
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    recreate()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(null)

    // Each summon starts without a stale screenshot — the user re-captures via "See screen".
    ScreenContextHolder.screenshot = null

    // If the last-used model is a cloud one, ready it immediately and never touch local models.
    val cloudActive = ChatPrefs(this).activeApiModelId != null
    if (!cloudActive) {
      // Kick off model list loading; we pick a downloaded model once it's ready.
      modelManagerViewModel.loadModelAllowlist()
    }

    setContent {
      GalleryTheme {
        val mmState by modelManagerViewModel.uiState.collectAsState()

        // The allowlist load may finish successfully (loading flips false) or fail (the base app
        // leaves loading=true but sets an error). Treat either as "done" so we never hang.
        val allowlistDone =
          !mmState.loadingModelAllowlist || mmState.loadingModelAllowlistError.isNotEmpty()
        LaunchedEffect(cloudActive, allowlistDone) {
          if (cloudActive) {
            // prepare() reads activeApiModelId and ignores the (null) local model.
            assistantViewModel.prepare(null)
          } else if (allowlistDone) {
            val downloaded = modelManagerViewModel.getAllDownloadedModels()
            val selected = modelManagerViewModel.getSelectedModel()
            val model =
              downloaded.firstOrNull { it.name == selected?.name } ?: downloaded.firstOrNull()
            assistantViewModel.prepare(model)
          }
        }

        AssistantPopup(
          viewModel = assistantViewModel,
          onClose = { finish() },
          onOpenApp = {
            // Continuing in the app: publish the popup transcript so the chat adopts and saves it.
            assistantViewModel.publishHandoff()
            startActivity(
              Intent(this@AssistantOverlayActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
          },
        )
      }
    }
  }
}
