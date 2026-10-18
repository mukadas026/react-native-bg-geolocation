package com.bggeolocation.runtime

import com.bggeolocation.core.TrackingConfig
import org.json.JSONObject

data class RuntimeConfig(
  val tracking: TrackingConfig = TrackingConfig(),
  val url: String? = null,
  val headers: Map<String, String> = emptyMap(),
  val notificationTitle: String = "Tracking location",
  val notificationText: String = "Location tracking is active",
  val motionPollInterval: Long = 300,
  val useMotionSensors: Boolean = true,
) {
  fun toJson(): String = JSONObject().apply {
    put("distanceFilter", tracking.distanceFilter)
    put("stopTimeout", tracking.stopTimeout)
    put("stationaryRadius", tracking.stationaryRadius)
    put("autoSync", tracking.autoSync)
    put("maxBatchSize", tracking.maxBatchSize)
    put("syncRetryBaseDelay", tracking.syncRetryBaseDelay)
    put("syncRetryMaxDelay", tracking.syncRetryMaxDelay)
    put("maxRecordsToPersist", tracking.maxRecordsToPersist)
    put("url", url)
    put("headers", JSONObject(headers))
    put("notificationTitle", notificationTitle)
    put("notificationText", notificationText)
    put("motionPollInterval", motionPollInterval)
    put("useMotionSensors", useMotionSensors)
  }.toString()

  companion object {
    fun fromJson(json: String): RuntimeConfig = fromJsonObject(JSONObject(json))

    fun fromJsonObject(json: JSONObject): RuntimeConfig {
      val d = TrackingConfig()
      val defaults = RuntimeConfig()
      val headers = json.optJSONObject("headers")?.let { obj ->
        obj.keys().asSequence().associateWith { obj.getString(it) }
      } ?: emptyMap()
      return RuntimeConfig(
        tracking = TrackingConfig(
          distanceFilter = json.optDouble("distanceFilter", d.distanceFilter),
          stopTimeout = json.optInt("stopTimeout", d.stopTimeout),
          stationaryRadius = json.optDouble("stationaryRadius", d.stationaryRadius),
          autoSync = json.optBoolean("autoSync", d.autoSync),
          maxBatchSize = json.optInt("maxBatchSize", d.maxBatchSize),
          syncRetryBaseDelay = json.optLong("syncRetryBaseDelay", d.syncRetryBaseDelay),
          syncRetryMaxDelay = json.optLong("syncRetryMaxDelay", d.syncRetryMaxDelay),
          maxRecordsToPersist = json.optInt("maxRecordsToPersist", d.maxRecordsToPersist),
        ),
        url = json.optString("url").takeIf { it.isNotEmpty() && it != "null" },
        headers = headers,
        notificationTitle = json.optString("notificationTitle", defaults.notificationTitle),
        notificationText = json.optString("notificationText", defaults.notificationText),
        motionPollInterval = json.optLong("motionPollInterval", defaults.motionPollInterval),
        useMotionSensors = json.optBoolean("useMotionSensors", defaults.useMotionSensors),
      )
    }
  }
}
