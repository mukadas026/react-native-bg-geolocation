import CoreLocation
import Foundation
import Network
import UIKit

@objc(BgGeoRuntime)
public final class BgGeoRuntime: NSObject, EngineCallbacks {
  @objc public static let shared = BgGeoRuntime()

  @objc public var onEvent: ((String, [String: Any]) -> Void)?

  private let fixStore = SqliteFixStore(path: SqliteFixStore.defaultPath())
  private let geofenceStore = SqliteGeofenceStore(path: SqliteFixStore.defaultPath())
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

  @objc public func addGeofences(
    _ list: [[String: Any]], completion: @escaping (NSError?) -> Void
  ) {
    DispatchQueue.main.async {
      var failure: NSError?
      for entry in list {
        guard let geofence = Self.parseGeofence(entry) else {
          failure = failure ?? Self.error(2, "invalid geofence")
          continue
        }
        let output = self.ensureCore().addGeofence(geofence, at: self.now())
        self.apply(output)
        switch output.result {
        case .limit:
          failure = failure ?? Self.error(1, "geofence limit of \(Self.geofenceLimit) reached")
        case .invalid:
          failure = failure ?? Self.error(2, "invalid geofence '\(geofence.id)'")
        default:
          break
        }
      }
      self.syncNativeGeofences()
      completion(failure)
    }
  }

  @objc public func removeGeofences(_ ids: [String], completion: @escaping () -> Void) {
    DispatchQueue.main.async {
      ids.forEach { self.apply(self.ensureCore().removeGeofence(id: $0, at: self.now())) }
      self.syncNativeGeofences()
      completion()
    }
  }

  @objc public func geofenceList() -> [[String: Any]] {
    geofenceStore.all().map { stored in
      let g = stored.geofence
      return [
        "identifier": g.id,
        "latitude": g.lat,
        "longitude": g.lon,
        "radius": g.radius,
        "notifyOnEntry": g.notifyOnEntry,
        "notifyOnExit": g.notifyOnExit,
      ]
    }
  }

  @objc public func providerState(completion: @escaping ([String: Any]) -> Void) {
    DispatchQueue.global().async {
      let enabled = CLLocationManager.locationServicesEnabled()
      DispatchQueue.main.async { completion(self.providerStateDictionary(enabled: enabled)) }
    }
  }

  @objc public func requestPermission(completion: @escaping ([String: Any]) -> Void) {
    DispatchQueue.main.async {
      self.engineInstance().requestPermission {
        self.providerState(completion: completion)
      }
    }
  }

  @objc public func capabilities() -> [String: Any] {
    [
      "platform": "ios",
      "engine": "corelocation",
      "geofenceLimit": Self.geofenceLimit,
      "geofenceMinRadius": 100,
      "activityRecognition": false,
      "canRelaunchAfterKill": true,
      "supportsHeadlessJs": false,
      "managedDevice": false,
      "motionSource": engine?.motionSource as Any,
    ]
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

  func engineDidTransition(identifier: String, action: GeofenceAction) {
    apply(ensureCore().onGeofenceTransition(id: identifier, action: action, at: now()))
  }

  func engineDidChangeAuthorization() {
    providerState { [weak self] state in self?.onEvent?("providerchange", state) }
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
    _ = engineInstance()

    var tracking = config.tracking
    tracking.autoSync = tracking.autoSync && config.url != nil
    tracking.geofenceLimit = Self.geofenceLimit
    let created = TrackingCore(
      config: tracking, fixStore: fixStore, stateStore: stateStore, online: online,
      geofenceStore: geofenceStore
    )
    core = created
    engineMode = .off
    apply(created.onLaunch(at: now()))
    syncNativeGeofences()
    return created
  }

  private func engineInstance() -> CoreLocationEngine {
    if let engine { return engine }
    let created = CoreLocationEngine(callbacks: self)
    engine = created
    return created
  }

  private func syncNativeGeofences() {
    engineInstance().syncGeofences(geofenceStore.all().map { $0.geofence })
  }

  private func providerStateDictionary(enabled: Bool) -> [String: Any] {
    let engine = engineInstance()
    return [
      "enabled": enabled,
      "authorization": engine.authorizationDescription,
      "accuracy": engine.accuracyDescription,
    ]
  }

  private static let geofenceLimit = 19

  private static func parseGeofence(_ entry: [String: Any]) -> Geofence? {
    guard let id = entry["identifier"] as? String, !id.isEmpty,
      let lat = (entry["latitude"] as? NSNumber)?.doubleValue,
      let lon = (entry["longitude"] as? NSNumber)?.doubleValue,
      let radius = (entry["radius"] as? NSNumber)?.doubleValue
    else { return nil }
    return Geofence(
      id: id, lat: lat, lon: lon, radius: radius,
      notifyOnEntry: entry["notifyOnEntry"] as? Bool ?? true,
      notifyOnExit: entry["notifyOnExit"] as? Bool ?? true
    )
  }

  private static func error(_ code: Int, _ message: String) -> NSError {
    NSError(domain: "BgGeo", code: code, userInfo: [NSLocalizedDescriptionKey: message])
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
    case .geofence(let id, let action):
      name = "geofence"
      payload = ["identifier": id, "action": action.rawValue]
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
