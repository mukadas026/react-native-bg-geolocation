public final class TrackingCore {
  private let config: TrackingConfig
  private let fixStore: FixStore
  private let stateStore: StateStore
  private let geofenceStore: GeofenceStore
  private var online: Bool

  private final class Tracked {
    let geofence: Geofence
    var inside: Bool?

    init(_ geofence: Geofence, inside: Bool?) {
      self.geofence = geofence
      self.inside = inside
    }
  }

  private var geofences: [Tracked]
  private var geofenceResult: GeofenceResult?

  private var state: TrackingState
  private var anchor: Fix?
  private var anchorSince: Int64 = 0
  private var lastRecorded: Fix?
  private var forceNextRecord = false

  private var inFlight: [Fix]?
  private var failures = 0
  private var retryAt: Int64?

  private var events: [CoreEvent] = []
  private var pendingUpload: UploadRequest?

  public init(
    config: TrackingConfig,
    fixStore: FixStore,
    stateStore: StateStore,
    online: Bool = true,
    geofenceStore: GeofenceStore = InMemoryGeofenceStore()
  ) {
    self.config = config
    self.fixStore = fixStore
    self.stateStore = stateStore
    self.online = online
    self.geofenceStore = geofenceStore
    self.state = stateStore.trackingState
    self.geofences = geofenceStore.all().map { Tracked($0.geofence, inside: $0.inside) }
  }

  public var trackingState: TrackingState { state }

  public var geofenceIds: [String] { geofences.map { $0.geofence.id } }

  public var engineState: EngineState {
    switch state {
    case .moving: return .continuous
    case .stationary: return .armed
    case .stopped: return .off
    }
  }

  public var nextDueAt: Int64? {
    var due: [Int64] = []
    if state == .moving, anchor != nil {
      due.append(anchorSince + Int64(config.stopTimeout) * 60_000)
    }
    if let retryAt { due.append(retryAt) }
    return due.min()
  }

  public func onLaunch(at: Int64) -> Output {
    run(at) { autoUpload() }
  }

  public func start(at: Int64) -> Output {
    run(at) {
      guard state == .stopped else { return }
      transition(to: .moving)
      lastRecorded = nil
      forceNextRecord = false
      events.append(.enabledChange(true))
    }
  }

  public func stop(at: Int64) -> Output {
    run(at) {
      guard state != .stopped else { return }
      transition(to: .stopped)
      events.append(.enabledChange(false))
    }
  }

  public func changePace(isMoving: Bool, at: Int64) -> Output {
    run(at) {
      if isMoving {
        resume()
      } else if state == .moving {
        settle()
      }
    }
  }

  public func onMotionSignal(at: Int64) -> Output {
    run(at) { resume() }
  }

  public func tick(at: Int64) -> Output {
    run(at) {}
  }

  public func onConnectivity(online isOnline: Bool, at: Int64) -> Output {
    run(at) {
      online = isOnline
      if online {
        failures = 0
        retryAt = nil
        autoUpload()
      }
    }
  }

  public func addGeofence(_ geofence: Geofence, at: Int64) -> Output {
    run(at) {
      guard geofence.radius.isFinite, geofence.radius > 0 else {
        geofenceResult = .invalid
        return
      }
      if let index = geofences.firstIndex(where: { $0.geofence.id == geofence.id }) {
        geofences[index] = Tracked(geofence, inside: nil)
        geofenceStore.save(geofence, inside: nil)
        geofenceResult = .ok
      } else if geofences.count >= config.geofenceLimit {
        geofenceResult = .limit
      } else {
        geofences.append(Tracked(geofence, inside: nil))
        geofenceStore.save(geofence, inside: nil)
        geofenceResult = .ok
      }
    }
  }

  public func removeGeofence(id: String, at: Int64) -> Output {
    run(at) {
      guard let index = geofences.firstIndex(where: { $0.geofence.id == id }) else {
        geofenceResult = .unknown
        return
      }
      geofences.remove(at: index)
      geofenceStore.remove(id: id)
      geofenceResult = .removed
    }
  }

  public func onGeofenceCheck(_ fix: Fix, at: Int64) -> Output {
    run(at) {
      if state != .stopped { evaluateGeofences(fix) }
    }
  }

  public func onGeofenceTransition(id: String, action: GeofenceAction, at: Int64) -> Output {
    run(at) {
      guard state != .stopped,
        let tracked = geofences.first(where: { $0.geofence.id == id })
      else { return }
      applyReading(tracked, inside: action == .enter)
    }
  }

  public func onFix(_ fix: Fix, at: Int64) -> Output {
    run(at) {
      guard state == .moving else { return }

      if let current = anchor {
        if distanceMeters(fix, current) > config.stationaryRadius {
          anchor = fix
          anchorSince = at
        }
      } else {
        anchor = fix
        anchorSince = at
      }

      let shouldRecord: Bool
      if let last = lastRecorded {
        shouldRecord = forceNextRecord || distanceMeters(fix, last) >= config.distanceFilter
      } else {
        shouldRecord = true
      }

      if shouldRecord {
        lastRecorded = fix
        forceNextRecord = false
        fixStore.append(fix)
        events.append(.location(fix.id))
        enforceRetention()

        let retryPending = retryAt.map { at < $0 } ?? false
        if !retryPending { autoUpload() }
      }

      evaluateGeofences(fix)
    }
  }

  public func onUploadResult(status: Int, at: Int64) -> Output {
    run(at) {
      guard let batch = inFlight else { return }
      inFlight = nil
      if (200..<300).contains(status) {
        fixStore.delete(ids: batch.map { $0.id })
        failures = 0
        retryAt = nil
        upload()
      } else {
        failures += 1
        let exponent = min(failures - 1, 30)
        let delayMs = min(
          config.syncRetryBaseDelay * 1000 * (Int64(1) << Int64(exponent)),
          config.syncRetryMaxDelay * 1000
        )
        retryAt = at + delayMs
      }
    }
  }

  private func run(_ at: Int64, _ block: () -> Void) -> Output {
    events = []
    pendingUpload = nil
    geofenceResult = nil
    block()
    evaluateStop(at)
    if let due = retryAt, at >= due { upload() }
    return Output(events: events, upload: pendingUpload, result: geofenceResult)
  }

  private func transition(to next: TrackingState) {
    state = next
    stateStore.trackingState = next
    anchor = nil
  }

  private func settle() {
    transition(to: .stationary)
    events.append(.motionChange(false))
  }

  private func resume() {
    guard state == .stationary else { return }
    transition(to: .moving)
    forceNextRecord = true
    events.append(.motionChange(true))
  }

  private func evaluateStop(_ at: Int64) {
    guard state == .moving, anchor != nil else { return }
    if at - anchorSince >= Int64(config.stopTimeout) * 60_000 { settle() }
  }

  private func enforceRetention() {
    let max = config.maxRecordsToPersist
    guard max >= 0, fixStore.size > max else { return }
    let dropped = fixStore.dropOldest(count: fixStore.size - max)
    events.append(.bufferOverflow(dropped.map { $0.id }))
  }

  private func evaluateGeofences(_ fix: Fix) {
    for tracked in geofences {
      let center = Fix(id: "", lat: tracked.geofence.lat, lon: tracked.geofence.lon)
      applyReading(tracked, inside: distanceMeters(fix, center) <= tracked.geofence.radius)
    }
  }

  private func applyReading(_ tracked: Tracked, inside: Bool) {
    let previous = tracked.inside
    tracked.inside = inside
    geofenceStore.setInside(id: tracked.geofence.id, inside: inside)
    let geofence = tracked.geofence
    if inside, previous != true, geofence.notifyOnEntry {
      events.append(.geofence(id: geofence.id, action: .enter))
    } else if !inside, previous == true, geofence.notifyOnExit {
      events.append(.geofence(id: geofence.id, action: .exit))
    }
  }

  private func autoUpload() {
    if config.autoSync { upload() }
  }

  private func upload() {
    guard online, inFlight == nil else { return }
    let batch = fixStore.oldest(limit: config.maxBatchSize)
    guard !batch.isEmpty else { return }
    inFlight = batch
    pendingUpload = UploadRequest(fixes: batch)
  }
}
