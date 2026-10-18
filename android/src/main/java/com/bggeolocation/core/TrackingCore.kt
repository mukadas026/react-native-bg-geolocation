package com.bggeolocation.core

class TrackingCore(
  private val config: TrackingConfig,
  private val fixStore: FixStore,
  private val stateStore: StateStore,
  private var online: Boolean = true,
) {
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
    if (!shouldRecord) return@run

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
    block()
    evaluateStop(at)
    val due = retryAt
    if (due != null && at >= due) upload()
    return Output(events.toList(), pendingUpload)
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
