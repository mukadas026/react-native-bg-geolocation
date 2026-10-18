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

  public init() {}
}

public enum CoreEvent: Equatable {
  case enabledChange(Bool)
  case motionChange(Bool)
  case location(String)
  case bufferOverflow([String])
}

public struct UploadRequest: Equatable {
  public let fixes: [Fix]
}

public struct Output {
  public let events: [CoreEvent]
  public let upload: UploadRequest?
}
