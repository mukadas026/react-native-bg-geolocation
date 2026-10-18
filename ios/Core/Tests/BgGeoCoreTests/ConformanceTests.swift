import Foundation
import XCTest

@testable import BgGeoCore

private let metersPerDegree = 111194.93

final class ConformanceTests: XCTestCase {
  func testFixtures() throws {
    let root = URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent().deletingLastPathComponent()
      .deletingLastPathComponent().deletingLastPathComponent()
      .deletingLastPathComponent()
      .appendingPathComponent("conformance/fixtures")

    let enumerator = try XCTUnwrap(FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil))
    let files = enumerator.compactMap { $0 as? URL }
      .filter { $0.pathExtension == "json" }
      .sorted { $0.path < $1.path }
    XCTAssertFalse(files.isEmpty, "no fixtures found in \(root.path)")

    for file in files {
      let name = file.path.replacingOccurrences(of: root.path + "/", with: "")
      let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
      try run(json, name: name)
    }
  }

  private func run(_ fixture: [String: Any], name: String) throws {
    let config = parseConfig(fixture["config"] as? [String: Any])
    let server = (fixture["server"] as? [Int]) ?? []
    var serverIndex = 0

    let fixStore = InMemoryFixStore()
    let stateStore = InMemoryStateStore()
    var online = true
    var core = TrackingCore(config: config, fixStore: fixStore, stateStore: stateStore, online: online)

    let steps = try XCTUnwrap(fixture["steps"] as? [[String: Any]])
    for (index, step) in steps.enumerated() {
      let atSeconds = try XCTUnwrap(step["at"] as? NSNumber).doubleValue
      let at = Int64((atSeconds * 1000).rounded())
      let input = try XCTUnwrap(step["input"] as? [String: Any])
      let type = try XCTUnwrap(input["type"] as? String)
      let where_ = "\(name) step \(index) (\(type) @\(atSeconds))"

      var output: Output
      switch type {
      case "start": output = core.start(at: at)
      case "stop": output = core.stop(at: at)
      case "changePace": output = core.changePace(isMoving: input["isMoving"] as! Bool, at: at)
      case "fix":
        let fix = Fix(
          id: input["id"] as! String,
          lat: (input["dy"] as! NSNumber).doubleValue / metersPerDegree,
          lon: (input["dx"] as! NSNumber).doubleValue / metersPerDegree
        )
        output = core.onFix(fix, at: at)
      case "motionSignal": output = core.onMotionSignal(at: at)
      case "tick": output = core.tick(at: at)
      case "connectivity":
        online = input["online"] as! Bool
        output = core.onConnectivity(online: online, at: at)
      case "restart":
        core = TrackingCore(config: config, fixStore: fixStore, stateStore: stateStore, online: online)
        output = core.onLaunch(at: at)
      default:
        XCTFail("\(where_): unknown input")
        return
      }

      var events = output.events
      var requests: [[String]] = []
      while let upload = output.upload {
        requests.append(upload.fixes.map { $0.id })
        let status = serverIndex < server.count ? server[serverIndex] : 200
        serverIndex += 1
        output = core.onUploadResult(status: status, at: at)
        events += output.events
      }

      guard let expect = step["expect"] as? [String: Any] else { continue }
      if let state = expect["state"] as? String {
        XCTAssertEqual(core.trackingState.rawValue, state, "\(where_): state")
      }
      if let engine = expect["engine"] as? String {
        XCTAssertEqual(core.engineState.rawValue, engine, "\(where_): engine")
      }
      if let expected = expect["events"] as? [[String: Any]] {
        XCTAssertEqual(events.map(describe), expected.map(describeExpected), "\(where_): events")
      }
      if let expected = expect["requests"] as? [[String]] {
        XCTAssertEqual(requests, expected, "\(where_): requests")
      }
      if let expected = expect["buffer"] as? [String] {
        XCTAssertEqual(fixStore.oldest(limit: Int.max).map { $0.id }, expected, "\(where_): buffer")
      }
    }
  }

  private func parseConfig(_ json: [String: Any]?) -> TrackingConfig {
    var config = TrackingConfig()
    guard let json else { return config }
    if let v = json["distanceFilter"] as? NSNumber { config.distanceFilter = v.doubleValue }
    if let v = json["stopTimeout"] as? Int { config.stopTimeout = v }
    if let v = json["stationaryRadius"] as? NSNumber { config.stationaryRadius = v.doubleValue }
    if let v = json["autoSync"] as? Bool { config.autoSync = v }
    if let v = json["maxBatchSize"] as? Int { config.maxBatchSize = v }
    if let v = json["syncRetryBaseDelay"] as? Int { config.syncRetryBaseDelay = Int64(v) }
    if let v = json["syncRetryMaxDelay"] as? Int { config.syncRetryMaxDelay = Int64(v) }
    if let v = json["maxRecordsToPersist"] as? Int { config.maxRecordsToPersist = v }
    return config
  }

  private func describe(_ event: CoreEvent) -> String {
    switch event {
    case .enabledChange(let enabled): return "enabledchange:\(enabled)"
    case .motionChange(let isMoving): return "motionchange:\(isMoving)"
    case .location(let id): return "location:\(id)"
    case .bufferOverflow(let dropped): return "buffer-overflow:\(dropped.joined(separator: ","))"
    }
  }

  private func describeExpected(_ event: [String: Any]) -> String {
    switch event["type"] as! String {
    case "enabledchange": return "enabledchange:\(event["enabled"] as! Bool)"
    case "motionchange": return "motionchange:\(event["isMoving"] as! Bool)"
    case "location": return "location:\(event["id"] as! String)"
    case "buffer-overflow": return "buffer-overflow:\((event["dropped"] as! [String]).joined(separator: ","))"
    default: return "unknown"
    }
  }
}

final class NextDueTests: XCTestCase {
  private func makeCore(stopTimeout: Int = 1) -> TrackingCore {
    var config = TrackingConfig()
    config.stopTimeout = stopTimeout
    return TrackingCore(config: config, fixStore: InMemoryFixStore(), stateStore: InMemoryStateStore())
  }

  func testNothingDueWhileStopped() {
    XCTAssertNil(makeCore().nextDueAt)
  }

  func testStopDeadlineStartsAtFirstFix() {
    let core = makeCore()
    _ = core.start(at: 0)
    XCTAssertNil(core.nextDueAt)
    _ = core.onFix(Fix(id: "f1", lat: 0, lon: 0), at: 5_000)
    XCTAssertEqual(core.nextDueAt, 65_000)
  }

  func testRetryIsDueAfterFailedUpload() {
    let core = makeCore(stopTimeout: 5)
    _ = core.start(at: 0)
    _ = core.onFix(Fix(id: "f1", lat: 0, lon: 0), at: 1_000)
    _ = core.onUploadResult(status: 500, at: 1_000)
    XCTAssertEqual(core.nextDueAt, 11_000)
  }

  func testNothingDueWhileStationaryAndIdle() {
    let core = makeCore()
    _ = core.start(at: 0)
    _ = core.onFix(Fix(id: "f1", lat: 0, lon: 0), at: 0)
    _ = core.onUploadResult(status: 200, at: 0)
    _ = core.tick(at: 60_000)
    XCTAssertNil(core.nextDueAt)
  }
}
