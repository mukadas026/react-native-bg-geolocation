package com.bggeolocation.storage

import android.content.Context
import com.bggeolocation.core.StateStore
import com.bggeolocation.core.TrackingState
import com.bggeolocation.runtime.RuntimeConfig

private const val PREFS = "bggeo"

class PrefsStateStore(context: Context) : StateStore {
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  override var trackingState: TrackingState
    get() = prefs.getString("trackingState", null)
      ?.let { runCatching { TrackingState.valueOf(it) }.getOrNull() }
      ?: TrackingState.STOPPED
    set(value) {
      prefs.edit().putString("trackingState", value.name).commit()
    }
}

class PrefsConfigStore(context: Context) {
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(): RuntimeConfig =
    prefs.getString("config", null)?.let { RuntimeConfig.fromJson(it) } ?: RuntimeConfig()

  fun save(config: RuntimeConfig) {
    prefs.edit().putString("config", config.toJson()).commit()
  }
}
