// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.data

/** The kinds of actions an app bar button can trigger. */
enum class AppBarActionType {
  NO_ACTION,
  APP_SETTING,
  DOWNLOAD_MANAGER,
  NAVIGATE_UP,
  MENU,
}

/** Pairs an [AppBarActionType] with the callback invoked when the button is tapped. */
class AppBarAction(val actionType: AppBarActionType, val actionFn: () -> Unit)
