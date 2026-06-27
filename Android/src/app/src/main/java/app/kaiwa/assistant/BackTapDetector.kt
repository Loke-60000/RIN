// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.assistant

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Detects a quick double-tap on the back (or body) of the phone from the accelerometer.
 *
 * GrapheneOS exposes no system "double-tap to launch assistant" shortcut, so the app watches the
 * accelerometer itself. We high-pass the signal (subtract a slow gravity estimate) so a tap shows
 * up as a short "jolt"; a rising edge past [tapThreshold] — with a time-based refractory so one
 * knock isn't counted twice — is one tap, and two taps landing within [MIN_GAP_MS]..[MAX_GAP_MS]
 * fire [onDoubleTap].
 *
 * Crucially the refractory is time-based (not "wait until the phone is still again"), so a fast
 * double-tap where the body never fully settles between taps is still caught.
 */
class BackTapDetector(
  context: Context,
  private val tapThreshold: Float = DEFAULT_TAP_THRESHOLD,
  private val onDoubleTap: () -> Unit,
) : SensorEventListener {
  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

  private val gravity = FloatArray(3)
  private var primed = false
  private var prevJolt = 0f
  private var lastSpikeAt = 0L
  private var lastTapAt = 0L

  val isAvailable: Boolean
    get() = sensor != null

  fun start() {
    val s = sensor ?: return
    // FASTEST sampling catches a brief tap transient; if the high-rate permission is unavailable,
    // fall back to GAME (50 Hz) rather than crash the service.
    val ok = runCatching {
      sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
    }
    if (ok.isFailure) {
      Log.w(TAG, "Fast sensor rate unavailable, falling back to GAME", ok.exceptionOrNull())
      sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }
  }

  fun stop() {
    sensorManager.unregisterListener(this)
    primed = false
    prevJolt = 0f
    lastSpikeAt = 0L
    lastTapAt = 0L
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (!primed) {
      System.arraycopy(event.values, 0, gravity, 0, 3)
      primed = true
      return
    }

    // Slow low-pass tracks gravity; the remainder is the tap transient (high-pass).
    gravity[0] = GRAVITY_ALPHA * gravity[0] + (1 - GRAVITY_ALPHA) * event.values[0]
    gravity[1] = GRAVITY_ALPHA * gravity[1] + (1 - GRAVITY_ALPHA) * event.values[1]
    gravity[2] = GRAVITY_ALPHA * gravity[2] + (1 - GRAVITY_ALPHA) * event.values[2]
    val hx = event.values[0] - gravity[0]
    val hy = event.values[1] - gravity[1]
    val hz = event.values[2] - gravity[2]
    val jolt = sqrt(hx * hx + hy * hy + hz * hz)

    val now = event.timestamp / 1_000_000L // ns -> ms
    val rising = jolt >= tapThreshold && prevJolt < tapThreshold
    prevJolt = jolt

    if (!rising || now - lastSpikeAt < REFRACTORY_MS) return
    lastSpikeAt = now

    val gap = now - lastTapAt
    if (DEBUG) Log.d(TAG, "tap jolt=%.2f gap=%d".format(jolt, if (lastTapAt == 0L) -1 else gap))
    if (lastTapAt != 0L && gap in MIN_GAP_MS..MAX_GAP_MS) {
      lastTapAt = 0L
      if (DEBUG) Log.d(TAG, "DOUBLE-TAP (gap=$gap)")
      onDoubleTap()
    } else {
      lastTapAt = now
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

  companion object {
    private const val TAG = "BackTapDetector"
    // Flip on to log every detected tap's jolt magnitude + inter-tap gap for calibration.
    private const val DEBUG = false

    // The high-pass jolt (m/s²) a tap must exceed, spanning the usable range: a low threshold
    // triggers on the gentlest tap (and stray handling), a high one needs a firm knock. ~2.4 was
    // too twitchy and ~9 barely fired, so the slider lives in that band.
    const val MIN_THRESHOLD = 3.0f
    const val MAX_THRESHOLD = 9.0f
    const val DEFAULT_TAP_THRESHOLD = 6.0f

    /**
     * Maps a 0–100 sensitivity (higher = more sensitive) to a jolt threshold. 100 % → easiest to
     * trigger ([MIN_THRESHOLD]); 0 % → needs the firmest tap ([MAX_THRESHOLD]).
     */
    fun thresholdForSensitivity(percent: Int): Float {
      val p = percent.coerceIn(0, 100) / 100f
      return MAX_THRESHOLD - p * (MAX_THRESHOLD - MIN_THRESHOLD)
    }
    // Gravity follows slowly (alpha near 1) so the fast tap transient stays in the high-pass.
    private const val GRAVITY_ALPHA = 0.85f
    private const val REFRACTORY_MS = 60L
    private const val MIN_GAP_MS = 80L
    private const val MAX_GAP_MS = 700L
  }
}
