import Foundation

struct RuntimeConfig {
  var tracking = TrackingConfig()
  var url: String?
  var headers: [String: String] = [:]
  var notificationTitle = "Tracking location"
  var notificationText = "Location tracking is active"

  init() {}

  init(json: [String: Any]) {
    func number(_ key: String) -> Double? { (json[key] as? NSNumber)?.doubleValue }

    if let v = number("distanceFilter") { tracking.distanceFilter = v }
    if let v = number("stopTimeout") { tracking.stopTimeout = Int(v) }
    if let v = number("stationaryRadius") { tracking.stationaryRadius = v }
    if let v = json["autoSync"] as? Bool { tracking.autoSync = v }
    if let v = number("maxBatchSize") { tracking.maxBatchSize = Int(v) }
    if let v = number("syncRetryBaseDelay") { tracking.syncRetryBaseDelay = Int64(v) }
    if let v = number("syncRetryMaxDelay") { tracking.syncRetryMaxDelay = Int64(v) }
    if let v = number("maxRecordsToPersist") { tracking.maxRecordsToPersist = Int(v) }
    if let v = json["url"] as? String, !v.isEmpty { url = v }
    if let v = json["headers"] as? [String: String] { headers = v }
    if let v = json["notificationTitle"] as? String { notificationTitle = v }
    if let v = json["notificationText"] as? String { notificationText = v }
  }

  var json: [String: Any] {
    var result: [String: Any] = [
      "distanceFilter": tracking.distanceFilter,
      "stopTimeout": tracking.stopTimeout,
      "stationaryRadius": tracking.stationaryRadius,
      "autoSync": tracking.autoSync,
      "maxBatchSize": tracking.maxBatchSize,
      "syncRetryBaseDelay": tracking.syncRetryBaseDelay,
      "syncRetryMaxDelay": tracking.syncRetryMaxDelay,
      "maxRecordsToPersist": tracking.maxRecordsToPersist,
      "headers": headers,
      "notificationTitle": notificationTitle,
      "notificationText": notificationText,
    ]
    if let url { result["url"] = url }
    return result
  }
}

final class UserDefaultsStateStore: StateStore {
  private let defaults: UserDefaults
  private let key = "bggeo.trackingState"

  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
  }

  var trackingState: TrackingState {
    get { defaults.string(forKey: key).flatMap(TrackingState.init(rawValue:)) ?? .stopped }
    set {
      defaults.set(newValue.rawValue, forKey: key)
      defaults.synchronize()
    }
  }
}

final class UserDefaultsConfigStore {
  private let defaults: UserDefaults
  private let key = "bggeo.config"

  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
  }

  func load() -> RuntimeConfig {
    guard let data = defaults.data(forKey: key),
      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return RuntimeConfig() }
    return RuntimeConfig(json: json)
  }

  func save(_ config: RuntimeConfig) {
    guard let data = try? JSONSerialization.data(withJSONObject: config.json) else { return }
    defaults.set(data, forKey: key)
    defaults.synchronize()
  }
}
