package com.bggeolocation.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.bggeolocation.core.AccelerometerMotionDetector

class AospEngine(
  private val context: Context,
  private val looper: Looper,
  private val callbacks: EngineCallbacks,
) : LocationEngine {
  private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val handler = Handler(looper)

  private val significantMotion: Sensor? =
    sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
  private val stepDetector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
  private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

  private val motionDetector = AccelerometerMotionDetector()
  private var locating = false
  private var lastLocation: Location? = null

  override var motionSource: MotionSource? = null
    private set

  private var pollOptions: ArmOptions? = null
  private val pollRunnable = Runnable { pollForMovement() }

  private val locationListener = object : LocationListener {
    override fun onLocationChanged(location: Location) {
      lastLocation = location
      callbacks.onLocation(location)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit
  }

  private val significantMotionTrigger = object : TriggerEventListener() {
    override fun onTrigger(event: TriggerEvent) {
      handler.post { signalMotion() }
    }
  }

  private val stepListener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      signalMotion()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
  }

  private val accelerometerListener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      val v = event.values
      if (motionDetector.onSample(event.timestamp, v[0], v[1], v[2])) {
        signalMotion()
      }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
  }

  @SuppressLint("MissingPermission")
  override fun startContinuous(distanceFilter: Double) {
    stop()
    val provider = pickProvider() ?: run {
      callbacks.onEngineError("no usable location provider")
      return
    }
    try {
      locationManager.requestLocationUpdates(
        provider, 1000L, distanceFilter.toFloat(), locationListener, looper
      )
      locating = true
    } catch (e: SecurityException) {
      callbacks.onEngineError("location permission missing: ${e.message}")
    }
  }

  override fun arm(options: ArmOptions) {
    stopLocating()
    disarm()
    val source = when {
      options.useMotionSensors && armSignificantMotion() -> MotionSource.SIGNIFICANT_MOTION
      options.useMotionSensors && armStepDetector() -> MotionSource.STEP_DETECTOR
      options.useMotionSensors && armAccelerometer() -> MotionSource.ACCELEROMETER
      else -> {
        armGpsPoll(options)
        MotionSource.GPS_POLL
      }
    }
    motionSource = source
    callbacks.onMotionSource(source)
  }

  override fun stop() {
    stopLocating()
    disarm()
  }

  private fun armSignificantMotion(): Boolean {
    val sensor = significantMotion ?: return false
    return sensorManager.requestTriggerSensor(significantMotionTrigger, sensor)
  }

  private fun armStepDetector(): Boolean {
    val sensor = stepDetector ?: return false
    if (!hasActivityRecognitionPermission()) return false
    return try {
      sensorManager.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
    } catch (_: SecurityException) {
      false
    }
  }

  private fun armAccelerometer(): Boolean {
    val sensor = accelerometer ?: return false
    motionDetector.reset()
    return sensorManager.registerListener(
      accelerometerListener,
      sensor,
      SensorManager.SENSOR_DELAY_NORMAL,
      ACCELEROMETER_BATCH_LATENCY_US,
      handler,
    )
  }

  private fun armGpsPoll(options: ArmOptions) {
    pollOptions = options
    handler.removeCallbacks(pollRunnable)
    handler.postDelayed(pollRunnable, options.pollIntervalMs)
  }

  private fun disarm() {
    significantMotion?.let { sensorManager.cancelTriggerSensor(significantMotionTrigger, it) }
    sensorManager.unregisterListener(stepListener)
    sensorManager.unregisterListener(accelerometerListener)
    handler.removeCallbacks(pollRunnable)
    pollOptions = null
    motionSource = null
  }

  private fun signalMotion() {
    disarm()
    callbacks.onMotionSignal()
  }

  @SuppressLint("MissingPermission")
  private fun pollForMovement() {
    val options = pollOptions ?: return
    val provider = pickProvider()
    if (provider == null) {
      callbacks.onEngineError("no usable location provider for motion poll")
      reschedulePoll(options)
      return
    }
    val onFix = { location: Location? ->
      handler.post {
        if (pollOptions != null) {
          val reference = lastLocation
          if (location != null && reference != null &&
            location.distanceTo(reference) > options.stationaryRadius
          ) {
            signalMotion()
          } else {
            if (location != null && reference == null) lastLocation = location
            reschedulePoll(options)
          }
        }
      }
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        locationManager.getCurrentLocation(provider, null, context.mainExecutor) { onFix(it) }
      } else {
        @Suppress("DEPRECATION")
        locationManager.requestSingleUpdate(
          provider,
          object : LocationListener {
            override fun onLocationChanged(location: Location) {
              onFix(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit

            override fun onProviderEnabled(p: String) = Unit

            override fun onProviderDisabled(p: String) = Unit
          },
          looper,
        )
      }
    } catch (e: SecurityException) {
      callbacks.onEngineError("location permission missing: ${e.message}")
      reschedulePoll(options)
    }
  }

  private fun reschedulePoll(options: ArmOptions) {
    handler.removeCallbacks(pollRunnable)
    handler.postDelayed(pollRunnable, options.pollIntervalMs)
  }

  private fun stopLocating() {
    if (locating) {
      locationManager.removeUpdates(locationListener)
      locating = false
    }
  }

  private fun pickProvider(): String? = when {
    locationManager.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
    locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
    else -> null
  }

  private fun hasActivityRecognitionPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
      context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
      PackageManager.PERMISSION_GRANTED

  companion object {
    private const val ACCELEROMETER_BATCH_LATENCY_US = 10_000_000
  }
}
