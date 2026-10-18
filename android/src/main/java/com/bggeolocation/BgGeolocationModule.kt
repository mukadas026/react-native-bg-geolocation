package com.bggeolocation

import com.bggeolocation.core.Fix
import com.bggeolocation.core.Geofence
import com.bggeolocation.runtime.Permissions
import com.bggeolocation.runtime.RuntimeConfig
import com.bggeolocation.runtime.TrackingRuntime
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.PermissionAwareActivity
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

  override fun addGeofence(geofence: ReadableMap, promise: Promise) {
    addAll(listOf(geofence), promise)
  }

  override fun addGeofences(geofences: ReadableArray, promise: Promise) {
    val maps = (0 until geofences.size()).mapNotNull { geofences.getMap(it) }
    addAll(maps, promise)
  }

  override fun removeGeofence(identifier: String, promise: Promise) {
    runtime.removeGeofences(listOf(identifier)) { promise.resolve(true) }
  }

  override fun removeGeofences(identifiers: ReadableArray, promise: Promise) {
    val ids = (0 until identifiers.size()).mapNotNull { identifiers.getString(it) }
    runtime.removeGeofences(ids) { promise.resolve(true) }
  }

  override fun getGeofences(promise: Promise) {
    val array = Arguments.createArray()
    runtime.geofences().forEach { array.pushMap(toGeofenceMap(it)) }
    promise.resolve(array)
  }

  override fun getGeofence(identifier: String, promise: Promise) {
    val found = runtime.geofences().firstOrNull { it.id == identifier }
    if (found == null) {
      promise.reject("E_NOT_FOUND", "no geofence '$identifier'")
    } else {
      promise.resolve(toGeofenceMap(found))
    }
  }

  override fun geofenceExists(identifier: String, promise: Promise) {
    promise.resolve(runtime.geofences().any { it.id == identifier })
  }

  override fun requestPermission(promise: Promise) {
    val activity = reactApplicationContext.currentActivity as? PermissionAwareActivity
    if (activity == null) {
      promise.reject("E_NO_ACTIVITY", "permissions can only be requested while an activity is showing")
      return
    }
    UiThreadUtil.runOnUiThread {
      Permissions.request(reactApplicationContext, activity) {
        runtime.emitProviderChange()
        promise.resolve(toMap(runtime.providerState()))
      }
    }
  }

  override fun getProviderState(promise: Promise) {
    promise.resolve(toMap(runtime.providerState()))
  }

  override fun getCapabilities(promise: Promise) {
    promise.resolve(toMap(runtime.capabilities()))
  }

  private fun addAll(maps: List<ReadableMap>, promise: Promise) {
    val geofences = try {
      maps.map { toGeofence(it) }
    } catch (e: Exception) {
      promise.reject("E_INVALID_GEOFENCE", e.message, e)
      return
    }
    runtime.addGeofences(geofences) { result ->
      result.fold(
        onSuccess = { promise.resolve(true) },
        onFailure = { promise.reject("E_GEOFENCE", it.message, it) },
      )
    }
  }

  private fun toGeofence(map: ReadableMap): Geofence = Geofence(
    id = map.getString("identifier") ?: throw IllegalArgumentException("identifier is required"),
    lat = map.getDouble("latitude"),
    lon = map.getDouble("longitude"),
    radius = map.getDouble("radius"),
    notifyOnEntry = if (map.hasKey("notifyOnEntry")) map.getBoolean("notifyOnEntry") else true,
    notifyOnExit = if (map.hasKey("notifyOnExit")) map.getBoolean("notifyOnExit") else true,
  )

  private fun toGeofenceMap(geofence: Geofence): WritableMap =
    toMap(
      mapOf(
        "identifier" to geofence.id,
        "latitude" to geofence.lat,
        "longitude" to geofence.lon,
        "radius" to geofence.radius,
        "notifyOnEntry" to geofence.notifyOnEntry,
        "notifyOnExit" to geofence.notifyOnExit,
      )
    )

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
