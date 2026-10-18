public protocol FixStore: AnyObject {
  func append(_ fix: Fix)
  func oldest(limit: Int) -> [Fix]
  func delete(ids: [String])
  func dropOldest(count: Int) -> [Fix]
  var size: Int { get }
}

public struct StoredGeofence {
  public let geofence: Geofence
  public let inside: Bool?

  public init(geofence: Geofence, inside: Bool?) {
    self.geofence = geofence
    self.inside = inside
  }
}

public protocol GeofenceStore: AnyObject {
  func all() -> [StoredGeofence]
  func save(_ geofence: Geofence, inside: Bool?)
  func setInside(id: String, inside: Bool?)
  func remove(id: String)
}

public protocol StateStore: AnyObject {
  var trackingState: TrackingState { get set }
}

public final class InMemoryFixStore: FixStore {
  private var fixes: [Fix] = []

  public init() {}

  public func append(_ fix: Fix) {
    fixes.append(fix)
  }

  public func oldest(limit: Int) -> [Fix] {
    Array(fixes.prefix(limit))
  }

  public func delete(ids: [String]) {
    let doomed = Set(ids)
    fixes.removeAll { doomed.contains($0.id) }
  }

  public func dropOldest(count: Int) -> [Fix] {
    let dropped = Array(fixes.prefix(count))
    fixes.removeFirst(dropped.count)
    return dropped
  }

  public var size: Int { fixes.count }
}

public final class InMemoryStateStore: StateStore {
  public var trackingState: TrackingState = .stopped

  public init() {}
}

public final class InMemoryGeofenceStore: GeofenceStore {
  private var order: [String] = []
  private var entries: [String: StoredGeofence] = [:]

  public init() {}

  public func all() -> [StoredGeofence] {
    order.compactMap { entries[$0] }
  }

  public func save(_ geofence: Geofence, inside: Bool?) {
    if entries[geofence.id] == nil { order.append(geofence.id) }
    entries[geofence.id] = StoredGeofence(geofence: geofence, inside: inside)
  }

  public func setInside(id: String, inside: Bool?) {
    guard let existing = entries[id] else { return }
    entries[id] = StoredGeofence(geofence: existing.geofence, inside: inside)
  }

  public func remove(id: String) {
    order.removeAll { $0 == id }
    entries[id] = nil
  }
}
