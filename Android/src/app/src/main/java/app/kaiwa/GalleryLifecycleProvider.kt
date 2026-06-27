// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa

/** Tracks whether the app is currently in the foreground. */
interface AppLifecycleProvider {
  var isAppInForeground: Boolean
}

/** Simple in-memory [AppLifecycleProvider] backed by a mutable flag. */
class GalleryLifecycleProvider : AppLifecycleProvider {
  override var isAppInForeground: Boolean = false
}
