# Integrating into the host apps

This is a plan, not a record of changes: nothing in `nss-regional-verification-mobile` or `smart-attendance-mobile` has been edited. Read `known-gaps.md` first; several items below depend on it.

## What the library replaces, and what it doesn't (yet)

Both apps use foreground location only today:

| App | Foreground code today | Replaceable now? |
|---|---|---|
| nss-regional-verification-mobile | `@react-native-community/geolocation` in `src/providers/LocationProvider.jsx` (`setRNConfiguration`, `getCurrentPosition`, `watchPosition`, `clearWatch`), permissions through `react-native-permissions` | **No.** The library has no `getCurrentPosition` or `watchPosition` yet |
| smart-attendance-mobile | `expo-location` in `hooks/useLocation.js` (foreground permission, `getCurrentPositionAsync`, `watchPositionAsync`) and `reverseGeocodeAsync` on the home screen | **No**, same reason, and reverse geocoding is out of scope |

So integration is **additive**: keep the existing foreground code for on-screen position, and add this library for background tracking, buffered upload and geofences. Foreground and background permissions are the same OS permissions, so the two do not conflict, but the app should have one place that asks. Use `BackgroundGeolocation.requestPermission()` for the full sequence (foreground, notifications, activity recognition, background) and stop calling `react-native-permissions` for location once it is in place.

## Common steps

1. **Install.** The package is not published and `main` points at a build output (`lib/`) that does not exist in a fresh clone. Until it is published, install from a packed tarball (run `yarn prepare` and then `yarn pack` in this repo, then `yarn add ../path/to.tgz`) rather than a git URL. Installing from git has not been tested.
2. **Server endpoint.** Provide an ingest URL that accepts `POST` with `{ "location": [ { "uuid", "timestamp", "coords": { "latitude", "longitude", "accuracy", "speed", "heading", "altitude" } } ] }`, returns 2xx only after storing, and **dedupes on `uuid`** (uploads are retried, so duplicates are expected). Authentication headers go in the `headers` config.
3. **Configure once at startup**, before anything else touches tracking:

   ```ts
   await BackgroundGeolocation.ready({
     url: 'https://api.example.com/locations',
     headers: { Authorization: `Bearer ${token}` },
     distanceFilter: 10,
     stopTimeout: 5,          // minutes
     maxRecordsToPersist: 5000,
     notificationTitle: 'Location tracking',
     notificationText: 'Tracking is active',
   });
   ```

   Calling `ready()` again rebuilds the core and resets stop detection, so call it once per launch, not per screen. Token refresh is not handled: a changed `Authorization` header requires another `ready()` call (see known gaps, 401/403).
4. **Surface failures.** Subscribe to `onError` and read `getState().failure`. A missing permission is reported there instead of crashing.
5. **Decide per app when tracking runs** (for example start on login, stop on logout) and call `start()` and `stop()` accordingly.

## nss-regional-verification-mobile (Android, device owner, no Play Services)

- React Native 0.80 and minSdk 24 meet the library's floor (RN 0.76+, New Architecture). Check `android/gradle.properties` for `newArchEnabled`: the property was not present when this was checked, which depends on the RN 0.80 default. The library is New Architecture only.
- **Permissions on managed devices.** The app is device owner, so the app's own native code (its `KioskModule`) should grant location permissions by policy (`DevicePolicyManager.setPermissionGrantState` for fine, coarse and background location, and notifications) before tracking starts. The library never calls `DevicePolicyManager`. It detects what is already granted and skips prompting, and `getCapabilities().managedDevice` reports `true`.
- **Battery.** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is already declared; the exemption itself still has to be granted by the app or by policy.
- **Engine.** Play Services is disabled on these devices, which the AOSP engine is built for. Check each device model for a significant-motion sensor: without it the engine falls to the step detector, then the accelerometer, then a periodic GPS poll (`getState().motionSource` shows which). Expect the poll fallback to detect movement late on such devices.
- **Manifest merge.** The library contributes its own permissions, a location foreground service, a boot receiver and an alarm receiver. Confirm the merged manifest in the release build.
- Kiosk restrictions that block outgoing calls, SMS and Bluetooth do not affect tracking. Anything that blocks location itself, or restricts background services, would.

## smart-attendance-mobile (Expo 57, iOS and Android, Play Services, store distribution)

- **Config plugin.** Add `"react-native-bg-geolocation"` to `plugins` in `app.json`/`app.config.js`. On iOS it adds the `location` background mode and the two location usage descriptions; Android needs nothing from the plugin (the library manifest merges at build time). Verified on a throwaway Expo SDK 58 project with `expo prebuild` on both platforms; not yet run against this app.
- **Usage strings.** `expo-location` also writes `NSLocation*UsageDescription`. Set the wording in one place: the plugin keeps strings already present and only overrides them when given `iosAlwaysUsageDescription` or `iosWhenInUseUsageDescription`.
- **Existing local plugins.** The app already has `withIosSceneDelegate`, `withPodDeploymentTargetFix` and `withGradleMemory`. They are unrelated, but the iOS example in this repo needed the same scene-lifecycle fix on iOS 27, so keep that plugin.
- **Engine.** Devices with Play Services currently run on the AOSP engine because the Fused engine is not built (see known gaps). Tracking works; activity-recognition labels and native geofencing are not used.
- **Store review.** Background location needs the Play Console background-location and foreground-service (location) declarations, and an App Store review justification and usage text. Plan this before release.
- **iOS.** iOS 15+ only; geofence limit is 19 per device (`getCapabilities().geofenceLimit`). Background wake-up on a real iPhone is still unverified (see known gaps). Do not promise users tracking after a force-quit.

## Verification checklist before shipping to either app

1. A fresh install on a real device, `requestPermission()` accepted end to end (not yet verified on any device).
2. Tracking survives: screen off for an hour, app swiped away, device reboot (Android), and permission revoked then re-granted.
3. Airplane mode for several minutes with movement, then reconnect: the buffered fixes arrive in order and dedupe on `uuid`.
4. Server load: a batch of 100 fixes per request at your expected device count.
5. Battery: an overnight run at your real `distanceFilter` and `stopTimeout`.
6. NSS only: repeat on the actual device models in the fleet, with the actual kiosk policy applied and Play Services disabled.
