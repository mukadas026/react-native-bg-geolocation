import CoreLocation
import Foundation
import Network
import UIKit

@objc(BgGeoRuntime)
public final class BgGeoRuntime: NSObject, EngineCallbacks {
  @objc public static let shared = BgGeoRuntime()

  @objc public var onEvent: ((String, [String: Any]) -> Void)?

  private let fixStore = SqliteFixStore(path: SqliteFixStore.defaultPath())
  private let stateStore = UserDefaultsStateStore()
  private let configStore = UserDefaultsConfigStore()
  private let uploader = HttpUploader()
  private let monitor = NWPathMonitor()

  private var config: RuntimeConfig
  private var core: TrackingCore?
  private var engine: CoreLocationEngine?
  private var engineMode = EngineState.off
  private var online = true
  private var currentFix: Fix?
  private var timer: DispatchSourceTimer?
  private var uploadTask = UIBackgroundTaskIdentifier.invalid

  private override init() {
    config = configStore.load()
    super.init()
  }

  @objc public func launchIfNeeded() {
    DispatchQueue.main.async {
      guard self.stateStore.trackingState != .stopped else { return }
      _ = self.ensureCore()
    }
  }

  @objc public func configure(_ json: [String: Any], completion: @escaping () -> Void) {
    DispatchQueue.main.async {
      self.config = RuntimeConfig(json: json)
      self.configStore.save(self.config)
      self.core = nil
      _ = self.ensureCore()
      completion()
    }
  }

  @objc public func start(completion: @escaping (NSError?) -> Void) {
    DispatchQueue.main.async {
      self.apply(self.ensureCore().start(at: self.now()))
      completion(nil)
    }
  }

  @objc public func stop(completion: @escaping () -> Void) {
    DispatchQueue.main.async {
      self.apply(self.ensureCore().stop(at: self.now()))
      completion()
    }
  }

  @objc public func changePace(_ isMoving: Bool, completion: @escaping () -> Void) {
    DispatchQueue.main.async {
      self.apply(self.ensureCore().changePace(isMoving: isMoving, at: self.now()))
      completion()
    }
  }

  @objc public func snapshot() -> [String: Any] {
    let state = stateStore.trackingState
    var result: [String: Any] = [
      "enabled": state != .stopped,
      "trackingState": state.rawValue,
      "isMoving": state == .moving,
      "count": fixStore.size,
    ]
    if let source = engine?.motionSource { result["motionSource"] = source }
    return result
  }

  @objc public func locations() -> [[String: Any]] {
    fixStore.oldest(limit: Int.max).map { fix in
      var coords: [String: Any] = ["latitude": fix.lat, "longitude": fix.lon]
      if let v = fix.accuracy { coords["accuracy"] = v }
      if let v = fix.speed { coords["speed"] = v }
      if let v = fix.bearing { coords["heading"] = v }
      if let v = fix.altitude { coords["altitude"] = v }
      return ["uuid": fix.id, "timestamp": Double(fix.timestamp), "coords": coords]
    }
  }

  @objc public func destroyLocations() {
    fixStore.delete(ids: fixStore.oldest(limit: Int.max).map { $0.id })
  }

  func engineDidUpdate(location: CLLocation) {
    let fix = Fix(
      id: UUID().uuidString,
      lat: location.coordinate.latitude,
      lon: location.coordinate.longitude,
      timestamp: Int64(location.timestamp.timeIntervalSince1970 * 1000),
      accuracy: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil,
      speed: location.speed >= 0 ? location.speed : nil,
      bearing: location.course >= 0 ? location.course : nil,
      altitude: location.verticalAccuracy >= 0 ? location.altitude : nil
    )
    currentFix = fix
    apply(ensureCore().onFix(fix, at: now()))
    currentFix = nil
  }

  func engineDidSignalMotion() {
    apply(ensureCore().onMotionSignal(at: now()))
  }

  func engineDidReportMotionSource(_ source: String) {
    NSLog("[BgGeo] motion source %@", source)
    onEvent?("motionsource", ["source": source])
  }

  func engineDidFail(_ message: String) {
    NSLog("[BgGeo] engine error: %@", message)
    onEvent?("error", ["message": message])
  }

  private func ensureCore() -> TrackingCore {
    if let core { return core }
    startNetworkMonitor()
    if engine == nil { engine = CoreLocationEngine(callbacks: self) }

    var tracking = config.tracking
    tracking.autoSync = tracking.autoSync && config.url != nil
    let created = TrackingCore(
      config: tracking, fixStore: fixStore, stateStore: stateStore, online: online
    )
    core = created
    engineMode = .off
    apply(created.onLaunch(at: now()))
    return created
  }

  private func apply(_ output: Output) {
    guard let current = core else { return }
    output.events.forEach(dispatch)
    if let request = output.upload { upload(request.fixes) }
    reconcileEngine(current)
    scheduleTimer(current)
  }

  private func upload(_ fixes: [Fix]) {
    guard let url = config.url else { return }
    if uploadTask == .invalid {
      uploadTask = UIApplication.shared.beginBackgroundTask(withName: "bggeo.upload") { [weak self] in
        self?.endUploadTask()
      }
    }
    uploader.post(url: url, headers: config.headers, fixes: fixes) { [weak self] status in
      guard let self, let current = self.core else { return }
      let output = current.onUploadResult(status: status, at: self.now())
      self.apply(output)
      if output.upload == nil { self.endUploadTask() }
    }
  }

  private func endUploadTask() {
    guard uploadTask != .invalid else { return }
    UIApplication.shared.endBackgroundTask(uploadTask)
    uploadTask = .invalid
  }

  private func reconcileEngine(_ current: TrackingCore) {
    let desired = current.engineState
    guard desired != engineMode else { return }
    engineMode = desired
    switch desired {
    case .continuous: engine?.startContinuous(distanceFilter: config.tracking.distanceFilter)
    case .armed: engine?.arm(stationaryRadius: config.tracking.stationaryRadius)
    case .off: engine?.stop()
    }
  }

  private func scheduleTimer(_ current: TrackingCore) {
    timer?.cancel()
    timer = nil
    guard let due = current.nextDueAt else { return }
    let source = DispatchSource.makeTimerSource(queue: .main)
    source.schedule(deadline: .now() + .milliseconds(Int(max(0, due - now()))))
    source.setEventHandler { [weak self] in
      guard let self, let core = self.core else { return }
      self.apply(core.tick(at: self.now()))
    }
    source.resume()
    timer = source
  }

  private func dispatch(_ event: CoreEvent) {
    let name: String
    let payload: [String: Any]
    switch event {
    case .enabledChange(let enabled):
      name = "enabledchange"
      payload = ["enabled": enabled]
    case .motionChange(let isMoving):
      name = "motionchange"
      payload = ["isMoving": isMoving]
    case .location(let id):
      name = "location"
      payload = locationPayload(id: id)
    case .bufferOverflow(let dropped):
      name = "buffer-overflow"
      payload = ["dropped": dropped]
    }
    NSLog("[BgGeo] %@ %@", name, "\(payload)")
    onEvent?(name, payload)
  }

  private func locationPayload(id: String) -> [String: Any] {
    guard let fix = currentFix, fix.id == id else { return ["uuid": id] }
    var coords: [String: Any] = ["latitude": fix.lat, "longitude": fix.lon]
    if let v = fix.accuracy { coords["accuracy"] = v }
    if let v = fix.speed { coords["speed"] = v }
    if let v = fix.bearing { coords["heading"] = v }
    if let v = fix.altitude { coords["altitude"] = v }
    return ["uuid": id, "timestamp": Double(fix.timestamp), "coords": coords]
  }

  private func startNetworkMonitor() {
    monitor.pathUpdateHandler = { [weak self] path in
      DispatchQueue.main.async {
        guard let self else { return }
        let isOnline = path.status == .satisfied
        self.online = isOnline
        if let core = self.core {
          self.apply(core.onConnectivity(online: isOnline, at: self.now()))
        }
      }
    }
    monitor.start(queue: .main)
  }

  private func now() -> Int64 {
    Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
  }
}
