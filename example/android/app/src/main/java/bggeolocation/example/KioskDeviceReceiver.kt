package bggeolocation.example

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

class KioskDeviceReceiver : DeviceAdminReceiver() {
  companion object {
    fun componentName(context: Context) =
      ComponentName(context.applicationContext, KioskDeviceReceiver::class.java)
  }
}
