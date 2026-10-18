import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

final class SqliteFixStore: FixStore {
  private var db: OpaquePointer?

  init(path: String) {
    guard sqlite3_open(path, &db) == SQLITE_OK else { return }
    execute(
      """
      CREATE TABLE IF NOT EXISTS fix (
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        id TEXT NOT NULL UNIQUE,
        lat REAL NOT NULL,
        lon REAL NOT NULL,
        timestamp INTEGER NOT NULL,
        accuracy REAL,
        speed REAL,
        bearing REAL,
        altitude REAL
      )
      """
    )
  }

  deinit {
    sqlite3_close(db)
  }

  static func defaultPath() -> String {
    let directory = FileManager.default
      .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    return directory.appendingPathComponent("bggeo.db").path
  }

  func append(_ fix: Fix) {
    let sql =
      "INSERT OR IGNORE INTO fix (id, lat, lon, timestamp, accuracy, speed, bearing, altitude) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    var statement: OpaquePointer?
    guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return }
    defer { sqlite3_finalize(statement) }
    sqlite3_bind_text(statement, 1, fix.id, -1, sqliteTransient)
    sqlite3_bind_double(statement, 2, fix.lat)
    sqlite3_bind_double(statement, 3, fix.lon)
    sqlite3_bind_int64(statement, 4, fix.timestamp)
    bind(statement, 5, fix.accuracy)
    bind(statement, 6, fix.speed)
    bind(statement, 7, fix.bearing)
    bind(statement, 8, fix.altitude)
    sqlite3_step(statement)
  }

  func oldest(limit: Int) -> [Fix] {
    var statement: OpaquePointer?
    let sql =
      "SELECT id, lat, lon, timestamp, accuracy, speed, bearing, altitude FROM fix ORDER BY seq LIMIT ?"
    guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return [] }
    defer { sqlite3_finalize(statement) }
    sqlite3_bind_int64(statement, 1, Int64(min(limit, Int(Int32.max))))

    var fixes: [Fix] = []
    while sqlite3_step(statement) == SQLITE_ROW {
      fixes.append(
        Fix(
          id: String(cString: sqlite3_column_text(statement, 0)),
          lat: sqlite3_column_double(statement, 1),
          lon: sqlite3_column_double(statement, 2),
          timestamp: sqlite3_column_int64(statement, 3),
          accuracy: optionalDouble(statement, 4),
          speed: optionalDouble(statement, 5),
          bearing: optionalDouble(statement, 6),
          altitude: optionalDouble(statement, 7)
        )
      )
    }
    return fixes
  }

  func delete(ids: [String]) {
    execute("BEGIN")
    for id in ids {
      var statement: OpaquePointer?
      if sqlite3_prepare_v2(db, "DELETE FROM fix WHERE id = ?", -1, &statement, nil) == SQLITE_OK {
        sqlite3_bind_text(statement, 1, id, -1, sqliteTransient)
        sqlite3_step(statement)
      }
      sqlite3_finalize(statement)
    }
    execute("COMMIT")
  }

  func dropOldest(count: Int) -> [Fix] {
    let dropped = oldest(limit: count)
    delete(ids: dropped.map { $0.id })
    return dropped
  }

  var size: Int {
    var statement: OpaquePointer?
    guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM fix", -1, &statement, nil) == SQLITE_OK
    else { return 0 }
    defer { sqlite3_finalize(statement) }
    return sqlite3_step(statement) == SQLITE_ROW ? Int(sqlite3_column_int64(statement, 0)) : 0
  }

  private func execute(_ sql: String) {
    sqlite3_exec(db, sql, nil, nil, nil)
  }

  private func bind(_ statement: OpaquePointer?, _ index: Int32, _ value: Double?) {
    if let value {
      sqlite3_bind_double(statement, index, value)
    } else {
      sqlite3_bind_null(statement, index)
    }
  }

  private func optionalDouble(_ statement: OpaquePointer?, _ column: Int32) -> Double? {
    sqlite3_column_type(statement, column) == SQLITE_NULL
      ? nil : sqlite3_column_double(statement, column)
  }
}
