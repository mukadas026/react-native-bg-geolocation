package com.bggeolocation.core

interface FixStore {
  fun append(fix: Fix)
  fun oldest(limit: Int): List<Fix>
  fun delete(ids: List<String>)
  fun dropOldest(count: Int): List<Fix>
  fun size(): Int
}

data class StoredGeofence(val geofence: Geofence, val inside: Boolean?)

interface GeofenceStore {
  fun all(): List<StoredGeofence>
  fun save(geofence: Geofence, inside: Boolean?)
  fun setInside(id: String, inside: Boolean?)
  fun remove(id: String)
}

interface StateStore {
  var trackingState: TrackingState
}

class InMemoryFixStore : FixStore {
  private val fixes = ArrayDeque<Fix>()

  override fun append(fix: Fix) {
    fixes.addLast(fix)
  }

  override fun oldest(limit: Int): List<Fix> = fixes.take(limit)

  override fun delete(ids: List<String>) {
    val doomed = ids.toSet()
    fixes.removeAll { it.id in doomed }
  }

  override fun dropOldest(count: Int): List<Fix> =
    List(count.coerceAtMost(fixes.size)) { fixes.removeFirst() }

  override fun size(): Int = fixes.size
}

class InMemoryStateStore : StateStore {
  override var trackingState: TrackingState = TrackingState.STOPPED
}

class InMemoryGeofenceStore : GeofenceStore {
  private val entries = LinkedHashMap<String, StoredGeofence>()

  override fun all(): List<StoredGeofence> = entries.values.toList()

  override fun save(geofence: Geofence, inside: Boolean?) {
    entries[geofence.id] = StoredGeofence(geofence, inside)
  }

  override fun setInside(id: String, inside: Boolean?) {
    entries[id]?.let { entries[id] = it.copy(inside = inside) }
  }

  override fun remove(id: String) {
    entries.remove(id)
  }
}
