public protocol FixStore: AnyObject {
  func append(_ fix: Fix)
  func oldest(limit: Int) -> [Fix]
  func delete(ids: [String])
  func dropOldest(count: Int) -> [Fix]
  var size: Int { get }
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
