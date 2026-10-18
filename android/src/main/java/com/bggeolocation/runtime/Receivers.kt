package com.bggeolocation.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bggeolocation.core.TrackingState
import com.bggeolocation.storage.PrefsStateStore

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (PrefsStateStore(context).trackingState == TrackingState.STOPPED) return
    if (!Permissions.hasLocationPermission(context)) {
      TrackingRuntime.get(context).reportFailure(TrackingService.MISSING_PERMISSION)
      return
    }
    val service = Intent(context, TrackingService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(service)
    } else {
      context.startService(service)
    }
  }
}

class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    TrackingRuntime.get(context).tick()
  }
}
