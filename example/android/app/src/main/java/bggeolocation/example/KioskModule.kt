package bggeolocation.example

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.annotations.ReactModule

@ReactModule(name = KioskModule.NAME)
class KioskModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  private val context = reactContext.applicationContext
  private val admin = KioskDeviceReceiver.componentName(context)
  private val policyManager =
    context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

  private val isDeviceOwner: Boolean
    get() = policyManager.isDeviceOwnerApp(context.packageName)

  override fun getName() = NAME

  @ReactMethod
  fun isDeviceOwner(promise: Promise) {
    promise.resolve(isDeviceOwner)
  }

  @ReactMethod
  fun enableKioskMode(promise: Promise) = applyRestrictions(true, promise)

  @ReactMethod
  fun disableKioskMode(promise: Promise) = applyRestrictions(false, promise)

  private fun applyRestrictions(enable: Boolean, promise: Promise) {
    if (!isDeviceOwner) {
      promise.reject("NOT_DEVICE_OWNER", "App is not device owner")
      return
    }
    try {
      RESTRICTIONS.forEach { restriction ->
        if (enable) {
          policyManager.addUserRestriction(admin, restriction)
        } else {
          policyManager.clearUserRestriction(admin, restriction)
        }
      }
      promise.resolve(true)
    } catch (e: Exception) {
      promise.reject("ERROR", e.message)
    }
  }

  companion object {
    const val NAME = "Kiosk"

    private val RESTRICTIONS = listOf(
      UserManager.DISALLOW_SAFE_BOOT,
      UserManager.DISALLOW_FACTORY_RESET,
      UserManager.DISALLOW_ADD_USER,
      UserManager.DISALLOW_REMOVE_USER,
      UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
      UserManager.DISALLOW_MODIFY_ACCOUNTS,
      UserManager.DISALLOW_USB_FILE_TRANSFER,
      UserManager.DISALLOW_CONFIG_BLUETOOTH,
      UserManager.DISALLOW_SMS,
      UserManager.DISALLOW_OUTGOING_CALLS,
      UserManager.DISALLOW_CREATE_WINDOWS,
      UserManager.DISALLOW_AIRPLANE_MODE,
      UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE,
    )
  }
}
