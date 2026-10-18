import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

final class SqliteGeofenceStore: GeofenceStore {
  private var db: OpaquePointer?

  init(path: String) {
    guard sqlite3_open(path, &db) == SQLITE_OK else { return }
    sqlite3_exec(
      db,
      """
      CREATE TABLE IF NOT EXISTS geofence (
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        id TEXT NOT NULL UNIQUE,
        lat REAL NOT NULL,
        lon REAL NOT NULL,
        radius REAL NOT NULL,
        notify_entry INTEGER NOT NULL,
        notify_exit INTEGER NOT NULL,
        inside INTEGER
      )
      """,
      nil, nil, nil
    )
  }

  deinit {
    sqlite3_close(db)
  }

  func all() -> [StoredGeofence] {
    var statement: OpaquePointer?
    let sql =
      "SELECT id, lat, lon, radius, notify_entry, notify_exit, inside FROM geofence ORDER BY seq"
    guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return [] }
    defer { sqlite3_finalize(statement) }

    var result: [StoredGeofence] = []
    while sqlite3_step(statement) == SQLITE_ROW {
      let inside: Bool? =
        sqlite3_column_type(statement, 6) == SQLITE_NULL
        ? nil : sqlite3_column_int(statement, 6) != 0
      result.append(
        StoredGeofence(
          geofence: Geofence(
            id: String(cString: sqlite3_column_text(statement, 0)),
            lat: sqlite3_column_double(statement, 1),
            lon: sqlite3_column_double(statement, 2),
            radius: sqlite3_column_double(statement, 3),
            notifyOnEntry: sqlite3_column_int(statement, 4) != 0,
            notifyOnExit: sqlite3_column_int(statement, 5) != 0
          ),
          inside: inside
        )
      )
    }
    return result
  }

  func save(_ geofence: Geofence, inside: Bool?) {
    var statement: OpaquePointer?
    let update =
      "UPDATE geofence SET lat = ?, lon = ?, radius = ?, notify_entry = ?, notify_exit = ?, inside = ? WHERE id = ?"
    if sqlite3_prepare_v2(db, update, -1, &statement, nil) == SQLITE_OK {
      bindFields(statement, geofence, inside, firstIndex: 1)
      sqlite3_bind_text(statement, 7, geofence.id, -1, sqliteTransient)
      sqlite3_step(statement)
    }
    sqlite3_finalize(statement)
    if sqlite3_changes(db) > 0 { return }

    let insert =
      "INSERT INTO geofence (lat, lon, radius, notify_entry, notify_exit, inside, id) VALUES (?, ?, ?, ?, ?, ?, ?)"
    statement = nil
    if sqlite3_prepare_v2(db, insert, -1, &statement, nil) == SQLITE_OK {
      bindFields(statement, geofence, inside, firstIndex: 1)
      sqlite3_bind_text(statement, 7, geofence.id, -1, sqliteTransient)
      sqlite3_step(statement)
    }
    sqlite3_finalize(statement)
  }

  func setInside(id: String, inside: Bool?) {
    var statement: OpaquePointer?
    if sqlite3_prepare_v2(db, "UPDATE geofence SET inside = ? WHERE id = ?", -1, &statement, nil)
      == SQLITE_OK
    {
      if let inside {
        sqlite3_bind_int(statement, 1, inside ? 1 : 0)
      } else {
        sqlite3_bind_null(statement, 1)
      }
      sqlite3_bind_text(statement, 2, id, -1, sqliteTransient)
      sqlite3_step(statement)
    }
    sqlite3_finalize(statement)
  }

  func remove(id: String) {
    var statement: OpaquePointer?
    if sqlite3_prepare_v2(db, "DELETE FROM geofence WHERE id = ?", -1, &statement, nil) == SQLITE_OK {
      sqlite3_bind_text(statement, 1, id, -1, sqliteTransient)
      sqlite3_step(statement)
    }
    sqlite3_finalize(statement)
  }

  private func bindFields(
    _ statement: OpaquePointer?, _ geofence: Geofence, _ inside: Bool?, firstIndex: Int32
  ) {
    sqlite3_bind_double(statement, firstIndex, geofence.lat)
    sqlite3_bind_double(statement, firstIndex + 1, geofence.lon)
    sqlite3_bind_double(statement, firstIndex + 2, geofence.radius)
    sqlite3_bind_int(statement, firstIndex + 3, geofence.notifyOnEntry ? 1 : 0)
    sqlite3_bind_int(statement, firstIndex + 4, geofence.notifyOnExit ? 1 : 0)
    if let inside {
      sqlite3_bind_int(statement, firstIndex + 5, inside ? 1 : 0)
    } else {
      sqlite3_bind_null(statement, firstIndex + 5)
    }
  }
}
