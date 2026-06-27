// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.kaiwa.ui.common.filmGrain
import app.kaiwa.ui.modelmanager.ModelManagerViewModel
import app.kaiwa.ui.theme.GalleryTheme
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Single entry-point activity hosting the entire Compose UI. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private val modelManagerViewModel: ModelManagerViewModel by viewModels()

  // Guards so the splash exit and the time-based fallback never install the content twice.
  private var splashScreenAboutToExit: Boolean = false
  private var contentSet: Boolean = false

  override fun onCreate(savedInstanceState: Bundle?) {
    // Deliberately drop the saved instance state so Compose does not restore a previous screen;
    // after an OS-initiated kill we want to come back up cleanly on the home screen.
    super.onCreate(null)

    routeDeeplink(intent)

    modelManagerViewModel.loadModelAllowlist()

    val splashScreen = installSplashScreen()

    // Fallback: on Android builds where the system splash is skipped (for example right after a
    // force-quit), the exit listener may never fire, so show the content after a short delay.
    lifecycleScope.launch {
      delay(1000)
      if (!splashScreenAboutToExit) {
        installContent()
      }
    }

    // Cross-fade from the splash icon into the app: wait until the icon animation is nearly done,
    // swap in the real content, then fade out and detach the splash view.
    splashScreen.setOnExitAnimationListener { splashScreenView ->
      splashScreenAboutToExit = true

      val now = System.currentTimeMillis()
      val iconAnimationStartMs = splashScreenView.iconAnimationStartMillis
      val duration = splashScreenView.iconAnimationDurationMillis
      val fadeOut = ObjectAnimator.ofFloat(splashScreenView.view, View.ALPHA, 1f, 0f)
      fadeOut.interpolator = DecelerateInterpolator()
      fadeOut.duration = 300L
      fadeOut.doOnEnd { splashScreenView.remove() }
      lifecycleScope.launch {
        val setContentDelay = duration - (now - iconAnimationStartMs) - 300
        if (setContentDelay > 0) {
          delay(setContentDelay)
        }
        installContent()
        fadeOut.start()
      }
    }

    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Three-button navigation does not honor edge-to-edge unless contrast enforcement is off.
      // See: https://issuetracker.google.com/issues/298296168
      window.isNavigationBarContrastEnforced = false
    }
    // Keep the screen awake while the app is in use for a smoother demo experience.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  /** Installs the Compose hierarchy exactly once. */
  private fun installContent() {
    if (contentSet) {
      return
    }
    contentSet = true

    setContent {
      GalleryTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          // A subtle risograph / film-grain finish across the whole app adds depth and polish.
          Box(modifier = Modifier.fillMaxSize().filmGrain()) {
            GalleryApp(modelManagerViewModel = modelManagerViewModel)
          }

          // A mask painted with the splash background color, faded out to reveal the app content.
          var startMaskFadeout by remember { mutableStateOf(false) }
          LaunchedEffect(Unit) { startMaskFadeout = true }
          AnimatedVisibility(
            !startMaskFadeout,
            enter = fadeIn(animationSpec = snap(0)),
            exit =
              fadeOut(animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)),
          ) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
          }
        }
      }
    }

    @OptIn(ExperimentalApi::class)
    ExperimentalFlags.enableBenchmark = false
  }

  /**
   * Bridges FCM console "deeplink" extras into the activity. Web links open in a browser; everything
   * else is set as the intent data for the nav graph to consume.
   */
  private fun routeDeeplink(intent: Intent) {
    val link = intent.getStringExtra("deeplink") ?: return
    if (link.startsWith("http://") || link.startsWith("https://")) {
      startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
    } else {
      intent.data = link.toUri()
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    routeDeeplink(intent)
  }

  override fun onResume() {
    super.onResume()

    firebaseAnalytics?.logEvent(
      "app_open",
      bundleOf(
        "app_version" to BuildConfig.VERSION_NAME,
        "os_version" to Build.VERSION.SDK_INT.toString(),
        "device_model" to Build.MODEL,
      ),
    )
  }

  companion object {
    private const val TAG = "AGMainActivity"
  }
}
