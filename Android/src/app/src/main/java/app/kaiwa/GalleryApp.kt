// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.kaiwa.ui.modelmanager.ModelManagerViewModel
import app.kaiwa.ui.navigation.GalleryNavHost

/** Root composable hosting the app's navigation graph. */
@Composable
fun GalleryApp(
  navController: NavHostController = rememberNavController(),
  modelManagerViewModel: ModelManagerViewModel,
) {
  GalleryNavHost(navController = navController, modelManagerViewModel = modelManagerViewModel)
}
