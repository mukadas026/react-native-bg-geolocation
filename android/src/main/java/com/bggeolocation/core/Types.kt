package com.bggeolocation.core

enum class TrackingState { STOPPED, MOVING, STATIONARY }

enum class EngineState { OFF, CONTINUOUS, ARMED }

data class Fix(
  val id: String,
  val lat: Double,
  val lon: Double,
  val timestamp: Long = 0,
  val accuracy: Float? = null,
  val speed: Float? = null,
  val bearing: Float? = null,
  val altitude: Double? = null,
)

data class TrackingConfig(
  val distanceFilter: Double = 10.0,
  val stopTimeout: Int = 5,
  val stationaryRadius: Double = 25.0,
  val autoSync: Boolean = true,
  val maxBatchSize: Int = 100,
  val syncRetryBaseDelay: Long = 10,
  val syncRetryMaxDelay: Long = 300,
  val maxRecordsToPersist: Int = -1,
  val geofenceLimit: Int = 20,
)

data class Geofence(
  val id: String,
  val lat: Double,
  val lon: Double,
  val radius: Double,
  val notifyOnEntry: Boolean = true,
  val notifyOnExit: Boolean = true,
)

enum class GeofenceAction { ENTER, EXIT }

enum class GeofenceResult { OK, LIMIT, INVALID, REMOVED, UNKNOWN }

sealed interface CoreEvent {
  data class EnabledChange(val enabled: Boolean) : CoreEvent
  data class MotionChange(val isMoving: Boolean) : CoreEvent
  data class Location(val id: String) : CoreEvent
  data class BufferOverflow(val dropped: List<String>) : CoreEvent
  data class GeofenceTransition(val id: String, val action: GeofenceAction) : CoreEvent
}

data class UploadRequest(val fixes: List<Fix>)

data class Output(
  val events: List<CoreEvent>,
  val upload: UploadRequest?,
  val result: GeofenceResult? = null,
)
