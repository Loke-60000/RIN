// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import app.kaiwa.chat.MainChatViewModel
import app.kaiwa.chat.OkGemmaChatScreen
import app.kaiwa.ui.modelmanager.ModelManagerViewModel

/** The single navigation route exposed by the app: the OK Gemma chat surface. */
private const val ROUTE_CHAT = "chat"

/**
 * Hosts the app's navigation graph.
 *
 * The graph currently resolves to a single chat destination. While composed, it mirrors the host
 * lifecycle into [ModelManagerViewModel.setAppInForeground] so the model manager can release or
 * reacquire resources as the app moves in and out of the foreground.
 */
@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val chatViewModel: MainChatViewModel = hiltViewModel()

  DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_START,
          Lifecycle.Event.ON_RESUME -> modelManagerViewModel.setAppInForeground(foreground = true)
          Lifecycle.Event.ON_STOP,
          Lifecycle.Event.ON_PAUSE -> modelManagerViewModel.setAppInForeground(foreground = false)
          else -> Unit
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  NavHost(navController = navController, startDestination = ROUTE_CHAT, modifier = modifier) {
    composable(route = ROUTE_CHAT) {
      OkGemmaChatScreen(
        viewModel = chatViewModel,
        modelManagerViewModel = modelManagerViewModel,
        tosViewModel = hiltViewModel(),
      )
    }
  }
}
