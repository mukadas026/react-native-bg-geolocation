package com.bggeolocation.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bggeolocation.core.Fix
import com.bggeolocation.core.FixStore

class BgGeoDatabase(context: Context) : SQLiteOpenHelper(context, "bggeo.db", null, 1) {
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE fix (
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
      """.trimIndent()
    )
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

class SqliteFixStore(private val helper: BgGeoDatabase) : FixStore {
  override fun append(fix: Fix) {
    val values = ContentValues().apply {
      put("id", fix.id)
      put("lat", fix.lat)
      put("lon", fix.lon)
      put("timestamp", fix.timestamp)
      put("accuracy", fix.accuracy)
      put("speed", fix.speed)
      put("bearing", fix.bearing)
      put("altitude", fix.altitude)
    }
    helper.writableDatabase.insertWithOnConflict(
      "fix", null, values, SQLiteDatabase.CONFLICT_IGNORE
    )
  }

  override fun oldest(limit: Int): List<Fix> {
    val fixes = mutableListOf<Fix>()
    helper.readableDatabase.rawQuery(
      "SELECT id, lat, lon, timestamp, accuracy, speed, bearing, altitude " +
        "FROM fix ORDER BY seq LIMIT ?",
      arrayOf(limit.toString())
    ).use { cursor ->
      while (cursor.moveToNext()) {
        fixes += Fix(
          id = cursor.getString(0),
          lat = cursor.getDouble(1),
          lon = cursor.getDouble(2),
          timestamp = cursor.getLong(3),
          accuracy = if (cursor.isNull(4)) null else cursor.getFloat(4),
          speed = if (cursor.isNull(5)) null else cursor.getFloat(5),
          bearing = if (cursor.isNull(6)) null else cursor.getFloat(6),
          altitude = if (cursor.isNull(7)) null else cursor.getDouble(7),
        )
      }
    }
    return fixes
  }

  override fun delete(ids: List<String>) {
    val db = helper.writableDatabase
    db.beginTransaction()
    try {
      ids.forEach { db.delete("fix", "id = ?", arrayOf(it)) }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  override fun dropOldest(count: Int): List<Fix> {
    val dropped = oldest(count)
    delete(dropped.map { it.id })
    return dropped
  }

  override fun size(): Int =
    helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM fix", null).use { cursor ->
      cursor.moveToFirst()
      cursor.getInt(0)
    }
}
