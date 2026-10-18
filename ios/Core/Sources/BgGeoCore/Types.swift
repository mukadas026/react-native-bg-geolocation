public enum TrackingState: String {
  case stopped, moving, stationary
}

public enum EngineState: String {
  case off, continuous, armed
}

public struct Fix: Equatable {
  public let id: String
  public let lat: Double
  public let lon: Double
  public let timestamp: Int64
  public let accuracy: Double?
  public let speed: Double?
  public let bearing: Double?
  public let altitude: Double?

  public init(
    id: String,
    lat: Double,
    lon: Double,
    timestamp: Int64 = 0,
    accuracy: Double? = nil,
    speed: Double? = nil,
    bearing: Double? = nil,
    altitude: Double? = nil
  ) {
    self.id = id
    self.lat = lat
    self.lon = lon
    self.timestamp = timestamp
    self.accuracy = accuracy
    self.speed = speed
    self.bearing = bearing
    self.altitude = altitude
  }
}

public struct TrackingConfig {
  public var distanceFilter: Double = 10
  public var stopTimeout: Int = 5
  public var stationaryRadius: Double = 25
  public var autoSync: Bool = true
  public var maxBatchSize: Int = 100
  public var syncRetryBaseDelay: Int64 = 10
  public var syncRetryMaxDelay: Int64 = 300
  public var maxRecordsToPersist: Int = -1
  public var geofenceLimit: Int = 20

  public init() {}
}

public struct Geofence: Equatable {
  public let id: String
  public let lat: Double
  public let lon: Double
  public let radius: Double
  public let notifyOnEntry: Bool
  public let notifyOnExit: Bool

  public init(
    id: String, lat: Double, lon: Double, radius: Double,
    notifyOnEntry: Bool = true, notifyOnExit: Bool = true
  ) {
    self.id = id
    self.lat = lat
    self.lon = lon
    self.radius = radius
    self.notifyOnEntry = notifyOnEntry
    self.notifyOnExit = notifyOnExit
  }
}

public enum GeofenceAction: String {
  case enter = "ENTER"
  case exit = "EXIT"
}

public enum GeofenceResult: String {
  case ok, limit, invalid, removed, unknown
}

public enum CoreEvent: Equatable {
  case enabledChange(Bool)
  case motionChange(Bool)
  case location(String)
  case bufferOverflow([String])
  case geofence(id: String, action: GeofenceAction)
}

public struct UploadRequest: Equatable {
  public let fixes: [Fix]
}

public struct Output {
  public let events: [CoreEvent]
  public let upload: UploadRequest?
  public let result: GeofenceResult?

  public init(events: [CoreEvent], upload: UploadRequest?, result: GeofenceResult? = nil) {
    self.events = events
    self.upload = upload
    self.result = result
  }
}
