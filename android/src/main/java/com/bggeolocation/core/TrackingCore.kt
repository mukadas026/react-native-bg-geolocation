package com.bggeolocation.core

class TrackingCore(
  private val config: TrackingConfig,
  private val fixStore: FixStore,
  private val stateStore: StateStore,
  private var online: Boolean = true,
  private val geofenceStore: GeofenceStore = InMemoryGeofenceStore(),
) {
  private class Tracked(val geofence: Geofence, var inside: Boolean?)

  private val geofences = geofenceStore.all()
    .map { Tracked(it.geofence, it.inside) }
    .toMutableList()
  private var geofenceResult: GeofenceResult? = null

  private var state = stateStore.trackingState
  private var anchor: Fix? = null
  private var anchorSince = 0L
  private var lastRecorded: Fix? = null
  private var forceNextRecord = false

  private var inFlight: List<Fix>? = null
  private var failures = 0
  private var retryAt: Long? = null

  private val events = mutableListOf<CoreEvent>()
  private var pendingUpload: UploadRequest? = null

  val trackingState: TrackingState get() = state

  val geofenceIds: List<String> get() = geofences.map { it.geofence.id }

  val engineState: EngineState
    get() = when (state) {
      TrackingState.MOVING -> EngineState.CONTINUOUS
      TrackingState.STATIONARY -> EngineState.ARMED
      TrackingState.STOPPED -> EngineState.OFF
    }

  val nextDueAt: Long?
    get() {
      val stopAt = if (state == TrackingState.MOVING && anchor != null) {
        anchorSince + config.stopTimeout * 60_000L
      } else {
        null
      }
      return listOfNotNull(stopAt, retryAt).minOrNull()
    }

  fun onLaunch(at: Long): Output = run(at) {
    autoUpload()
  }

  fun start(at: Long): Output = run(at) {
    if (state != TrackingState.STOPPED) return@run
    transitionTo(TrackingState.MOVING)
    lastRecorded = null
    forceNextRecord = false
    events += CoreEvent.EnabledChange(true)
  }

  fun stop(at: Long): Output = run(at) {
    if (state == TrackingState.STOPPED) return@run
    transitionTo(TrackingState.STOPPED)
    events += CoreEvent.EnabledChange(false)
  }

  fun changePace(isMoving: Boolean, at: Long): Output = run(at) {
    if (isMoving) resume() else if (state == TrackingState.MOVING) settle()
  }

  fun onMotionSignal(at: Long): Output = run(at) { resume() }

  fun tick(at: Long): Output = run(at) {}

  fun onConnectivity(isOnline: Boolean, at: Long): Output = run(at) {
    online = isOnline
    if (online) {
      failures = 0
      retryAt = null
      autoUpload()
    }
  }

  fun addGeofence(geofence: Geofence, at: Long): Output = run(at) {
    if (!geofence.radius.isFinite() || geofence.radius <= 0) {
      geofenceResult = GeofenceResult.INVALID
      return@run
    }
    val index = geofences.indexOfFirst { it.geofence.id == geofence.id }
    when {
      index >= 0 -> {
        geofences[index] = Tracked(geofence, null)
        geofenceStore.save(geofence, null)
        geofenceResult = GeofenceResult.OK
      }
      geofences.size >= config.geofenceLimit -> geofenceResult = GeofenceResult.LIMIT
      else -> {
        geofences += Tracked(geofence, null)
        geofenceStore.save(geofence, null)
        geofenceResult = GeofenceResult.OK
      }
    }
  }

  fun removeGeofence(id: String, at: Long): Output = run(at) {
    val index = geofences.indexOfFirst { it.geofence.id == id }
    if (index < 0) {
      geofenceResult = GeofenceResult.UNKNOWN
    } else {
      geofences.removeAt(index)
      geofenceStore.remove(id)
      geofenceResult = GeofenceResult.REMOVED
    }
  }

  fun onGeofenceCheck(fix: Fix, at: Long): Output = run(at) {
    if (state != TrackingState.STOPPED) evaluateGeofences(fix)
  }

  fun onGeofenceTransition(id: String, action: GeofenceAction, at: Long): Output = run(at) {
    if (state == TrackingState.STOPPED) return@run
    geofences.firstOrNull { it.geofence.id == id }
      ?.let { applyReading(it, action == GeofenceAction.ENTER) }
  }

  fun onFix(fix: Fix, at: Long): Output = run(at) {
    if (state != TrackingState.MOVING) return@run

    val currentAnchor = anchor
    if (currentAnchor == null || distanceMeters(fix, currentAnchor) > config.stationaryRadius) {
      anchor = fix
      anchorSince = at
    }

    val last = lastRecorded
    val shouldRecord =
      last == null || forceNextRecord || distanceMeters(fix, last) >= config.distanceFilter

    if (shouldRecord) {
      lastRecorded = fix
      forceNextRecord = false
      fixStore.append(fix)
      events += CoreEvent.Location(fix.id)
      enforceRetention()

      val due = retryAt
      if (due == null || at >= due) {
        autoUpload()
      }
    }

    evaluateGeofences(fix)
  }

  fun onUploadResult(status: Int, at: Long): Output = run(at) {
    val batch = inFlight ?: return@run
    inFlight = null
    if (status in 200..299) {
      fixStore.delete(batch.map { it.id })
      failures = 0
      retryAt = null
      upload()
    } else {
      failures += 1
      val exponent = (failures - 1).coerceAtMost(30)
      val delayMs = minOf(
        config.syncRetryBaseDelay * 1000L * (1L shl exponent),
        config.syncRetryMaxDelay * 1000L,
      )
      retryAt = at + delayMs
    }
  }

  private fun run(at: Long, block: () -> Unit): Output {
    events.clear()
    pendingUpload = null
    geofenceResult = null
    block()
    evaluateStop(at)
    val due = retryAt
    if (due != null && at >= due) upload()
    return Output(events.toList(), pendingUpload, geofenceResult)
  }

  private fun transitionTo(next: TrackingState) {
    state = next
    stateStore.trackingState = next
    anchor = null
  }

  private fun settle() {
    transitionTo(TrackingState.STATIONARY)
    events += CoreEvent.MotionChange(false)
  }

  private fun resume() {
    if (state != TrackingState.STATIONARY) return
    transitionTo(TrackingState.MOVING)
    forceNextRecord = true
    events += CoreEvent.MotionChange(true)
  }

  private fun evaluateStop(at: Long) {
    if (state != TrackingState.MOVING || anchor == null) return
    if (at - anchorSince >= config.stopTimeout * 60_000L) settle()
  }

  private fun enforceRetention() {
    val max = config.maxRecordsToPersist
    if (max < 0 || fixStore.size() <= max) return
    val dropped = fixStore.dropOldest(fixStore.size() - max)
    events += CoreEvent.BufferOverflow(dropped.map { it.id })
  }

  private fun evaluateGeofences(fix: Fix) {
    geofences.forEach { tracked ->
      val center = Fix("", tracked.geofence.lat, tracked.geofence.lon)
      applyReading(tracked, distanceMeters(fix, center) <= tracked.geofence.radius)
    }
  }

  private fun applyReading(tracked: Tracked, inside: Boolean) {
    val previous = tracked.inside
    tracked.inside = inside
    geofenceStore.setInside(tracked.geofence.id, inside)
    val geofence = tracked.geofence
    if (inside && previous != true && geofence.notifyOnEntry) {
      events += CoreEvent.GeofenceTransition(geofence.id, GeofenceAction.ENTER)
    } else if (!inside && previous == true && geofence.notifyOnExit) {
      events += CoreEvent.GeofenceTransition(geofence.id, GeofenceAction.EXIT)
    }
  }

  private fun autoUpload() {
    if (config.autoSync) upload()
  }

  private fun upload() {
    if (!online || inFlight != null) return
    val batch = fixStore.oldest(config.maxBatchSize)
    if (batch.isEmpty()) return
    inFlight = batch
    pendingUpload = UploadRequest(batch)
  }
}
