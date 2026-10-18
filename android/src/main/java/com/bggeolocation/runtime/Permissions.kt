package com.bggeolocation.runtime

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener

enum class PermissionStep { FOREGROUND, NOTIFICATIONS, ACTIVITY_RECOGNITION, BACKGROUND }

object Permissions {
  fun isGranted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  fun hasLocationPermission(context: Context): Boolean =
    isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
      isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

  fun permissionsFor(context: Context, step: PermissionStep): Array<String> {
    val fine = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return when (step) {
      PermissionStep.FOREGROUND ->
        if (fine) {
          emptyArray()
        } else {
          arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
      PermissionStep.NOTIFICATIONS ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          !isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
          arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
          emptyArray()
        }
      PermissionStep.ACTIVITY_RECOGNITION ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
          !isGranted(context, Manifest.permission.ACTIVITY_RECOGNITION)
        ) {
          arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
          emptyArray()
        }
      PermissionStep.BACKGROUND ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (fine || coarse) &&
          !isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
          arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
          emptyArray()
        }
    }
  }

  fun providerState(context: Context): Map<String, Any?> {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    val network = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    val fine = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
      isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    val authorization = when {
      !fine && !coarse -> "denied"
      background -> "always"
      else -> "whenInUse"
    }
    val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      locationManager.isLocationEnabled
    } else {
      gps || network
    }
    return mapOf(
      "enabled" to enabled,
      "gps" to gps,
      "network" to network,
      "authorization" to authorization,
      "accuracy" to if (fine) "full" else "reduced",
      "permissions" to mapOf(
        "fineLocation" to fine,
        "coarseLocation" to coarse,
        "backgroundLocation" to background,
        "notifications" to (
          Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
          ),
        "activityRecognition" to (
          Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            isGranted(context, Manifest.permission.ACTIVITY_RECOGNITION)
          ),
      ),
    )
  }

  fun isManagedDevice(context: Context): Boolean {
    val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return manager.isDeviceOwnerApp(context.packageName)
  }

  fun request(
    context: Context,
    activity: PermissionAwareActivity,
    done: () -> Unit,
  ) {
    runStep(context, activity, PermissionStep.values().toList(), done)
  }

  private fun runStep(
    context: Context,
    activity: PermissionAwareActivity,
    remaining: List<PermissionStep>,
    done: () -> Unit,
  ) {
    if (remaining.isEmpty()) {
      done()
      return
    }
    val needed = permissionsFor(context, remaining.first())
    if (needed.isEmpty()) {
      runStep(context, activity, remaining.drop(1), done)
      return
    }
    activity.requestPermissions(
      needed,
      REQUEST_CODE,
      PermissionListener { requestCode, _, _ ->
        if (requestCode != REQUEST_CODE) return@PermissionListener false
        runStep(context, activity, remaining.drop(1), done)
        true
      },
    )
  }

  private const val REQUEST_CODE = 7422
}
