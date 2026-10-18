import CoreLocation
import Foundation

protocol EngineCallbacks: AnyObject {
  func engineDidUpdate(location: CLLocation)
  func engineDidSignalMotion()
  func engineDidReportMotionSource(_ source: String)
  func engineDidFail(_ message: String)
  func engineDidTransition(identifier: String, action: GeofenceAction)
  func engineDidChangeAuthorization()
}

final class CoreLocationEngine: NSObject, CLLocationManagerDelegate {
  private static let regionIdentifier = "bggeo.stationary"
  private static let geofencePrefix = "bggeo.gf."
  private static let minimumRegionRadius: CLLocationDistance = 100

  private enum Mode {
    case off
    case continuous
    case armed(radius: CLLocationDistance)
  }

  private let manager = CLLocationManager()
  private weak var callbacks: EngineCallbacks?
  private var mode = Mode.off
  private var lastLocation: CLLocation?
  private var regionCenter: CLLocation?
  private var awaitingRegionFix = false
  private var verifyingExistingRegion = false
  private var pendingAuthorizationAction: (() -> Void)?
  private var permissionWaiters: [() -> Void] = []

  private(set) var motionSource: String?

  init(callbacks: EngineCallbacks) {
    self.callbacks = callbacks
    super.init()
    manager.delegate = self
  }

  func startContinuous(distanceFilter: Double) {
    stop()
    guard isAuthorized else {
      requestAuthorization { [weak self] in self?.startContinuous(distanceFilter: distanceFilter) }
      return
    }
    guard backgroundModeDeclared else {
      callbacks?.engineDidFail("UIBackgroundModes must include 'location'")
      return
    }
    mode = .continuous
    manager.desiredAccuracy = kCLLocationAccuracyBest
    manager.distanceFilter = distanceFilter
    manager.activityType = .other
    manager.allowsBackgroundLocationUpdates = true
    manager.pausesLocationUpdatesAutomatically = false
    manager.showsBackgroundLocationIndicator = true
    manager.startUpdatingLocation()
  }

  func arm(stationaryRadius: Double) {
    manager.stopUpdatingLocation()
    guard isAuthorized else {
      requestAuthorization { [weak self] in self?.arm(stationaryRadius: stationaryRadius) }
      return
    }
    let radius = max(stationaryRadius, Self.minimumRegionRadius)
    mode = .armed(radius: radius)
    NSLog(
      "[BgGeo] arming: authorization=%d regionsMonitored=%d radius=%.0f",
      manager.authorizationStatus.rawValue, manager.monitoredRegions.count, radius
    )

    if let existing = existingRegion {
      regionCenter = regionCenter ?? CLLocation(
        latitude: existing.center.latitude, longitude: existing.center.longitude
      )
      manager.requestState(for: existing)
      verifyingExistingRegion = true
      manager.requestLocation()
    } else if let center = lastLocation ?? manager.location {
      monitorRegion(around: center, radius: radius)
    } else {
      awaitingRegionFix = true
      manager.requestLocation()
    }

    manager.startMonitoringSignificantLocationChanges()
    motionSource = "stationary-region"
    callbacks?.engineDidReportMotionSource("stationary-region")
  }

  func stop() {
    manager.stopUpdatingLocation()
    manager.stopMonitoringSignificantLocationChanges()
    stopMonitoringRegions()
    mode = .off
    motionSource = nil
    awaitingRegionFix = false
    verifyingExistingRegion = false
  }

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    switch mode {
    case .continuous:
      locations.forEach { location in
        lastLocation = location
        callbacks?.engineDidUpdate(location: location)
      }
    case .armed(let radius):
      guard let location = locations.last else { return }
      lastLocation = location
      if verifyingExistingRegion, let center = regionCenter {
        verifyingExistingRegion = false
        let distance = location.distance(from: center)
        NSLog("[BgGeo] verify region: %.0f m from centre (radius %.0f)", distance, radius)
        if distance > radius {
          signalMotion()
          return
        }
      }
      if awaitingRegionFix {
        awaitingRegionFix = false
        monitorRegion(around: location, radius: radius)
        return
      }
      if let center = regionCenter, location.distance(from: center) > radius {
        signalMotion()
      }
    case .off:
      break
    }
  }

  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    switch manager.authorizationStatus {
    case .authorizedWhenInUse:
      manager.requestAlwaysAuthorization()
      runPendingAuthorizationAction()
      if !permissionWaiters.isEmpty {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
          self?.finishPermissionWaiters()
        }
      }
    case .authorizedAlways:
      runPendingAuthorizationAction()
      finishPermissionWaiters()
    case .denied, .restricted:
      pendingAuthorizationAction = nil
      callbacks?.engineDidFail("location permission denied")
      finishPermissionWaiters()
    default:
      break
    }
    callbacks?.engineDidChangeAuthorization()
  }

  func locationManager(
    _ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion
  ) {
    NSLog("[BgGeo] region state %d for %@", state.rawValue, region.identifier)
    if let id = geofenceId(region) {
      switch state {
      case .inside: callbacks?.engineDidTransition(identifier: id, action: .enter)
      case .outside: callbacks?.engineDidTransition(identifier: id, action: .exit)
      default: break
      }
      return
    }
    guard region.identifier == Self.regionIdentifier, case .armed = mode, state == .outside
    else { return }
    signalMotion()
  }

  func locationManager(_ manager: CLLocationManager, didStartMonitoringFor region: CLRegion) {
    NSLog("[BgGeo] region monitoring started %@", region.identifier)
  }

  func locationManager(
    _ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error
  ) {
    callbacks?.engineDidFail("region monitoring failed: \(error.localizedDescription)")
  }

  func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
    if let id = geofenceId(region) {
      callbacks?.engineDidTransition(identifier: id, action: .enter)
    }
  }

  func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
    NSLog("[BgGeo] region exit %@", region.identifier)
    if let id = geofenceId(region) {
      callbacks?.engineDidTransition(identifier: id, action: .exit)
      return
    }
    guard region.identifier == Self.regionIdentifier, case .armed = mode else { return }
    signalMotion()
  }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    if (error as? CLError)?.code == .locationUnknown { return }
    callbacks?.engineDidFail("location error: \(error.localizedDescription)")
  }

  func syncGeofences(_ geofences: [Geofence]) {
    let wanted = Dictionary(
      uniqueKeysWithValues: geofences.map { (Self.geofencePrefix + $0.id, $0) }
    )
    var kept = Set<String>()

    for region in manager.monitoredRegions where region.identifier.hasPrefix(Self.geofencePrefix) {
      guard let geofence = wanted[region.identifier],
        let circular = region as? CLCircularRegion,
        circular.center.latitude == geofence.lat,
        circular.center.longitude == geofence.lon,
        circular.radius == min(geofence.radius, manager.maximumRegionMonitoringDistance)
      else {
        manager.stopMonitoring(for: region)
        continue
      }
      kept.insert(region.identifier)
    }

    for (identifier, geofence) in wanted where !kept.contains(identifier) {
      let region = CLCircularRegion(
        center: CLLocationCoordinate2D(latitude: geofence.lat, longitude: geofence.lon),
        radius: min(geofence.radius, manager.maximumRegionMonitoringDistance),
        identifier: identifier
      )
      region.notifyOnEntry = true
      region.notifyOnExit = true
      manager.startMonitoring(for: region)
      manager.requestState(for: region)
    }
  }

  var authorizationDescription: String {
    switch manager.authorizationStatus {
    case .authorizedAlways: return "always"
    case .authorizedWhenInUse: return "whenInUse"
    case .notDetermined: return "notDetermined"
    default: return "denied"
    }
  }

  var accuracyDescription: String {
    manager.accuracyAuthorization == .fullAccuracy ? "full" : "reduced"
  }

  func requestPermission(completion: @escaping () -> Void) {
    switch manager.authorizationStatus {
    case .notDetermined:
      permissionWaiters.append(completion)
      manager.requestWhenInUseAuthorization()
    case .authorizedWhenInUse:
      permissionWaiters.append(completion)
      manager.requestAlwaysAuthorization()
      DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
        self?.finishPermissionWaiters()
      }
    default:
      completion()
    }
  }

  private func finishPermissionWaiters() {
    let waiters = permissionWaiters
    permissionWaiters = []
    waiters.forEach { $0() }
  }

  private func geofenceId(_ region: CLRegion) -> String? {
    guard region.identifier.hasPrefix(Self.geofencePrefix) else { return nil }
    return String(region.identifier.dropFirst(Self.geofencePrefix.count))
  }

  private func requestAuthorization(then action: @escaping () -> Void) {
    switch manager.authorizationStatus {
    case .notDetermined:
      pendingAuthorizationAction = action
      manager.requestWhenInUseAuthorization()
    case .denied, .restricted:
      callbacks?.engineDidFail("location permission denied")
    default:
      action()
    }
  }

  private func runPendingAuthorizationAction() {
    guard let action = pendingAuthorizationAction else { return }
    pendingAuthorizationAction = nil
    action()
  }

  private var isAuthorized: Bool {
    switch manager.authorizationStatus {
    case .authorizedAlways, .authorizedWhenInUse: return true
    default: return false
    }
  }

  private var backgroundModeDeclared: Bool {
    let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String]
    return modes?.contains("location") == true
  }

  private var existingRegion: CLCircularRegion? {
    manager.monitoredRegions
      .first { $0.identifier == Self.regionIdentifier } as? CLCircularRegion
  }

  private func monitorRegion(around center: CLLocation, radius: CLLocationDistance) {
    let region = CLCircularRegion(
      center: center.coordinate,
      radius: min(radius, manager.maximumRegionMonitoringDistance),
      identifier: Self.regionIdentifier
    )
    region.notifyOnExit = true
    region.notifyOnEntry = false
    regionCenter = center
    manager.startMonitoring(for: region)
  }

  private func stopMonitoringRegions() {
    manager.monitoredRegions
      .filter { $0.identifier == Self.regionIdentifier }
      .forEach { manager.stopMonitoring(for: $0) }
    regionCenter = nil
  }

  private func signalMotion() {
    manager.stopMonitoringSignificantLocationChanges()
    stopMonitoringRegions()
    mode = .off
    motionSource = nil
    callbacks?.engineDidSignalMotion()
  }
}
