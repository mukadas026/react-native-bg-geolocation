package com.bggeolocation.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6371008.8

fun distanceMeters(a: Fix, b: Fix): Double {
  val rad = PI / 180
  val dLat = (b.lat - a.lat) * rad
  val dLon = (b.lon - a.lon) * rad
  val h = sin(dLat / 2).pow(2) + cos(a.lat * rad) * cos(b.lat * rad) * sin(dLon / 2).pow(2)
  return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
}
