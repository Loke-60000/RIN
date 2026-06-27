// Copyright 2025 Lokman Ramdani
// SPDX-License-Identifier: MIT

package app.kaiwa.customtasks.common

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reports whether the device has been held still (low gyroscope rotation) continuously for at least
 * [steadyDurationMs]. On hardware without a gyroscope, it reports steady from the start.
 */
class SteadinessMonitor(context: Context, private val steadyDurationMs: Long = 2000L) :
  SensorEventListener {

  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

  private val _isStable = MutableStateFlow(gyroSensor == null)
  val isStable: StateFlow<Boolean> = _isStable

  private var steadyStartTime: Long? = null

  fun start() {
    gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
  }

  fun stop() {
    sensorManager.unregisterListener(this)
  }

  override fun onSensorChanged(event: SensorEvent?) {
    if (event?.sensor?.type != Sensor.TYPE_GYROSCOPE) return

    val (x, y, z) = event.values
    val rotationMagnitude = sqrt(x * x + y * y + z * z)

    if (rotationMagnitude >= ROTATION_THRESHOLD_RAD_PER_SEC) {
      steadyStartTime = null
      _isStable.value = false
      return
    }

    val start = steadyStartTime ?: System.currentTimeMillis().also { steadyStartTime = it }
    _isStable.value = System.currentTimeMillis() - start >= steadyDurationMs
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

  private companion object {
    // 0.1 rad/s is low enough to count as held still.
    const val ROTATION_THRESHOLD_RAD_PER_SEC = 0.1f
  }
}
