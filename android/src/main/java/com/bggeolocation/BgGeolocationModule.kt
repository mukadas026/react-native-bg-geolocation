package com.bggeolocation

import com.bggeolocation.core.Fix
import com.bggeolocation.runtime.RuntimeConfig
import com.bggeolocation.runtime.TrackingRuntime
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import org.json.JSONObject

class BgGeolocationModule(reactContext: ReactApplicationContext) :
  NativeBgGeolocationSpec(reactContext) {

  private val runtime: TrackingRuntime
    get() = TrackingRuntime.get(reactApplicationContext)

  override fun initialize() {
    super.initialize()
    runtime.listener = com.bggeolocation.runtime.RuntimeListener { name, payload ->
      emitOnEvent(toMap(mapOf("name" to name) + payload))
    }
  }

  override fun invalidate() {
    runtime.listener = null
    super.invalidate()
  }

  override fun ready(config: ReadableMap, promise: Promise) {
    val parsed = RuntimeConfig.fromJsonObject(JSONObject(config.toHashMap()))
    runtime.configure(parsed) { promise.resolve(state()) }
  }

  override fun start(promise: Promise) {
    runtime.start { result ->
      result.fold(
        onSuccess = { promise.resolve(state()) },
        onFailure = { promise.reject("E_START", it.message, it) },
      )
    }
  }

  override fun stop(promise: Promise) {
    runtime.stop { promise.resolve(state()) }
  }

  override fun changePace(isMoving: Boolean, promise: Promise) {
    runtime.changePace(isMoving) { promise.resolve(state()) }
  }

  override fun getState(promise: Promise) {
    promise.resolve(state())
  }

  override fun getCount(promise: Promise) {
    promise.resolve((runtime.snapshot()["count"] as Int).toDouble())
  }

  override fun getLocations(promise: Promise) {
    val array = Arguments.createArray()
    runtime.locations().forEach { array.pushMap(toLocationMap(it)) }
    promise.resolve(array)
  }

  override fun destroyLocations(promise: Promise) {
    runtime.destroyLocations()
    promise.resolve(true)
  }

  private fun state(): WritableMap = toMap(runtime.snapshot())

  private fun toLocationMap(fix: Fix): WritableMap =
    toMap(
      mapOf(
        "uuid" to fix.id,
        "timestamp" to fix.timestamp.toDouble(),
        "coords" to mapOf(
          "latitude" to fix.lat,
          "longitude" to fix.lon,
          "accuracy" to fix.accuracy?.toDouble(),
          "speed" to fix.speed?.toDouble(),
          "heading" to fix.bearing?.toDouble(),
          "altitude" to fix.altitude,
        ),
      )
    )

  private fun toMap(source: Map<String, Any?>): WritableMap {
    val map = Arguments.createMap()
    source.forEach { (key, value) ->
      when (value) {
        null -> map.putNull(key)
        is Boolean -> map.putBoolean(key, value)
        is Int -> map.putInt(key, value)
        is Number -> map.putDouble(key, value.toDouble())
        is String -> map.putString(key, value)
        is List<*> -> map.putArray(key, Arguments.fromList(value))
        is Map<*, *> -> {
          @Suppress("UNCHECKED_CAST")
          map.putMap(key, toMap(value as Map<String, Any?>))
        }
        else -> map.putString(key, value.toString())
      }
    }
    return map
  }

  companion object {
    const val NAME = NativeBgGeolocationSpec.NAME
  }
}
