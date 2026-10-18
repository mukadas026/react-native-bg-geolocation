package com.bggeolocation.engine

import android.location.Location

enum class MotionSource(val label: String) {
  SIGNIFICANT_MOTION("significant-motion"),
  STEP_DETECTOR("step-detector"),
  ACCELEROMETER("accelerometer"),
  GPS_POLL("gps-poll"),
}

data class ArmOptions(
  val stationaryRadius: Double,
  val pollIntervalMs: Long,
  val useMotionSensors: Boolean,
)

interface EngineCallbacks {
  fun onLocation(location: Location)
  fun onMotionSignal()
  fun onMotionSource(source: MotionSource)
  fun onEngineError(message: String)
}

interface LocationEngine {
  val motionSource: MotionSource?

  fun startContinuous(distanceFilter: Double)
  fun arm(options: ArmOptions)
  fun stop()
}
