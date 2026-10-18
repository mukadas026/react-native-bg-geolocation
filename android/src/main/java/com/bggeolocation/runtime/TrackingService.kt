package com.bggeolocation.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.bggeolocation.core.TrackingState
import com.bggeolocation.storage.PrefsStateStore

class TrackingService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val runtime = TrackingRuntime.get(this)
    if (!Permissions.hasLocationPermission(this)) {
      runtime.reportFailure(MISSING_PERMISSION)
      stopSelf()
      return START_NOT_STICKY
    }

    val notification = buildNotification(runtime.config)
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (e: SecurityException) {
      runtime.reportFailure("could not start the location service: ${e.message}")
      stopSelf()
      return START_NOT_STICKY
    }

    if (PrefsStateStore(this).trackingState == TrackingState.STOPPED && intent == null) {
      stopSelf()
    } else {
      runtime.launch()
    }
    return START_STICKY
  }

  private fun buildNotification(config: RuntimeConfig): Notification {
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Location tracking", NotificationManager.IMPORTANCE_LOW)
      )
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }
    return builder
      .setContentTitle(config.notificationTitle)
      .setContentText(config.notificationText)
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setOngoing(true)
      .build()
  }

  companion object {
    const val MISSING_PERMISSION = "location permission missing"
    private const val CHANNEL_ID = "bggeo_tracking"
    private const val NOTIFICATION_ID = 7421
  }
}
