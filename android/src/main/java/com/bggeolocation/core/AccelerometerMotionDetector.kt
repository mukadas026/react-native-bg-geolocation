package com.bggeolocation.core

import kotlin.math.sqrt

class AccelerometerMotionDetector(
  private val windowNanos: Long = 5_000_000_000L,
  private val varianceThreshold: Double = 0.35,
  private val requiredWindows: Int = 2,
) {
  private var windowStart = 0L
  private var count = 0
  private var mean = 0.0
  private var squaredDeviations = 0.0
  private var activeWindows = 0

  fun onSample(timestampNanos: Long, x: Float, y: Float, z: Float): Boolean {
    if (count == 0) windowStart = timestampNanos

    val magnitude = sqrt((x * x + y * y + z * z).toDouble())
    count += 1
    val delta = magnitude - mean
    mean += delta / count
    squaredDeviations += delta * (magnitude - mean)

    if (timestampNanos - windowStart < windowNanos) return false

    val variance = if (count > 1) squaredDeviations / (count - 1) else 0.0
    count = 0
    mean = 0.0
    squaredDeviations = 0.0
    activeWindows = if (variance > varianceThreshold) activeWindows + 1 else 0
    return activeWindows >= requiredWindows
  }

  fun reset() {
    count = 0
    mean = 0.0
    squaredDeviations = 0.0
    activeWindows = 0
  }
}
