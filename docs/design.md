# Design: Background Geolocation Library

Status: milestones 1 to 4 built; milestone 5 done on Android and pending on iOS; 6 pending. Vocabulary is in `../GLOSSARY.md`, decisions in `adr/`, and what is unverified or unbuilt in `known-gaps.md`.

## 1. Goals and non-goals

**Goals (v1)**
- Continuous background tracking with battery-aware stop detection.
- Native SQLite buffer and HTTP sync that work with JS dead.
- Geofences (enter/exit), at most 20 per device.
- Native-core wake after kill/relaunch, with optional best-effort headless JS.
- Android with and without Google Play Services (managed device-owner devices and store-distributed apps); iOS 15+ (store-distributed apps only).
- Reliability as testable acceptance criteria (section 9).

**Non-goals (v1)**
- Scheduler, Transistorsoft auth tokens, log email/upload, >20 geofences, HMS engine.
- Guaranteed behavior after a user force-quits on iOS.
- Encryption of the buffer at rest.
- Old-architecture bridge support.

**First consumers:** `nss-regional-verification-mobile` (RN 0.80, no Expo, minSdk 24; Android only, device-owner managed, no Play Services) and `smart-attendance-mobile` (Expo 57, RN 0.86, New Architecture; iOS and Android, store-distributed, Play Services available, not device-owner). Both are foreground-only today. The admin-owned apps are Android-only, so iOS is needed only for store-distributed apps. Both must drop their own location-permission calls when adopting this library (it owns the permission flow).

## 2. Platform constraints driving the design

| Concern | Android | iOS |
|---|---|---|
| Continuous background | Foreground service with notification; `location` service type on 14+ | `allowsBackgroundLocationUpdates` + `location` background mode; status-bar indicator |
| Killed app | Process kill; BOOT_COMPLETED + watchdog restart (feasible on managed devices) | Relaunch only via significant-change, region exit or visit; short execution window, JS may not boot |
| Stationary power saving | GPS off, wait for motion signal | GPS off, ~100-200m region around last fix, wake on exit |
| Geofences | AOSP engine: evaluated by us. Fused engine: native client (limit 100) | Native region monitoring, hard cap 20, effective radius floor ~100-200m |
| Motion signal | AOSP: significant-motion sensor. Fused: ActivityRecognition | CMMotionActivity (needs motion permission) |
| Permissions | Fine/coarse, separate background prompt (11+), notifications (13+); granted by policy on managed devices | WhenInUse then Always upgrade (system may downgrade), reduced accuracy (14+), motion |
| No Play Services | AOSP engine, first-class (ADR-0002) | n/a |
| Play Services present | Fused engine, first-class (ADR-0002) | n/a |

## 3. Architecture

```
JS API (TS facade, Transistorsoft-compatible names)
        |  TurboModule spec
Native module shim (Kotlin / ObjC++ -> Swift)
        |
Tracking core (per platform, ADR-0003)
  - state machine: stopped | moving | stationary
  - buffer (SQLite)   - sync queue (HTTP)   - geofence evaluator
  - watchdog / self-health
        |
Engine interface
  Android: AospEngine (no Play Services), FusedEngine (Play Services)
  iOS:     CoreLocationEngine
```

Rules:
- The core never calls into JS to make a decision. JS receives events and sends commands (ADR-0001).
- Engines supply fixes and movement signals; the core owns every state transition. Platform differences are engine inputs, not separate rules (ADR-0003).
- The module must function when constructed by the OS with no React context (boot receiver, iOS relaunch).

## 4. Tracking state machine

States: `stopped`, `moving`, `stationary`. Same transitions on both platforms:

| From | Event | To | Actions |
|---|---|---|---|
| stopped | `start()` | moving | start engine, record fixes |
| moving | no movement beyond `stationaryRadius` for `stopTimeout` | stationary | stop continuous location, arm stationary trigger, emit motionchange(false) |
| stationary | movement trigger (Android: significant motion; iOS: region exit) | moving | resume continuous location, emit motionchange(true) |
| moving, stationary | `stop()` | stopped | tear down engine, keep buffer |
| any | `changePace(true/false)` | moving / stationary | manual override |

Open parameters (defaults to be fixed in the conformance fixtures): `distanceFilter`, `stopTimeout`, `stationaryRadius`, `desiredAccuracy`, `heartbeatInterval`.

### Android AOSP engine (devices without Play Services)
- Stop detection is done by the core from displacement: no fix beyond `stationaryRadius` of the anchor for `stopTimeout`. Speed is not used.
- Resume uses the first available source (ADR-0002): `TYPE_SIGNIFICANT_MOTION` (one-shot, re-armed), then `TYPE_STEP_DETECTOR` (needs ACTIVITY_RECOGNITION on Android 10+), then accelerometer variance over 5 s windows (two consecutive active windows), then a periodic GPS poll compared against the last known position. The chosen source is reported via `getState().motionSource`.
- Foreground service holds the process while Moving; Stationary keeps the service but releases GPS.
- No activity labels (`onActivityChange` unsupported; reported by capability flags).

### Android Fused engine (devices with Play Services)
- FusedLocationProvider + ActivityRecognition + native geofencing. Selected at runtime when Play Services is usable and `engine: 'auto'`; `engine: 'aosp'` forces the AOSP engine for testing.

### iOS engine
- Moving: standard location updates with background updates enabled.
- Stationary: stop updates, arm a region (radius max(`stationaryRadius`, 100 m)) around the last fix, plus significant-change as a safety net (ADR-0004).
- Relaunch handler boots the core natively (no JS), resumes the state, flushes the buffer within the available window.
- When an existing region survives a relaunch, the engine verifies it instead of trusting it: `requestState(for:)` plus a one-shot location check against the region centre, and it resumes immediately if the device is already outside.
- Region registration failures (`monitoringDidFailFor`) are reported as engine errors.
- iOS 17+ `CLBackgroundActivitySession` used behind availability checks.

## 5. Persistence and sync

Buffer (SQLite, native-owned, per ADR-0001):
- `fix`: `uuid` (PK), timestamp, lat, lon, accuracy, speed, heading, altitude, battery, is_moving, odometer, extras, `synced_state`.
- A fix is committed before any sync attempt and deleted only after a 2xx for its batch.
- Retention: `maxDaysToPersist`, `maxRecordsToPersist`. When the cap is hit the oldest synced-or-unsynced fix is dropped and a `buffer-overflow` event is recorded (data loss is surfaced, never silent).

Sync:
- POST JSON batch to `url` with configured headers/params: `{ "location": [ { "uuid", "timestamp" (ISO-8601 UTC), "coords": { "latitude", "longitude", "accuracy", "speed", "heading", "altitude" } }, ... ] }`. Optional coords fields are omitted when unknown.
- `batchSync`, `maxBatchSize`, `autoSync`, `autoSyncThreshold` mirror the Transistorsoft names.
- Retry with exponential backoff and jitter; server dedupes on `uuid`. Network change and app start trigger a flush.
- Non-2xx and timeouts leave the batch intact; auth failures (401/403) emit an `authorization`-style event and pause sync.

## 6. Geofences

- At most 20 per device (enforced on both platforms so behavior is identical; `addGeofence` past the cap rejects).
- iOS: native region monitoring. Radius below the practical minimum is clamped and reported via capability flags.
- AOSP engine: evaluated against each fix while Moving, on significant motion, and on a periodic low-power check while Stationary. Accepted latency: minutes.
- Fused engine: native geofencing client, re-registered after reboot and Play Services updates.
- Events: `onGeofence` (ENTER/EXIT), `onGeofencesChange`. Dwell is out of v1.

## 7. Permissions and managed devices

- The library owns the flow: `requestPermission()` handles WhenInUse to Always on iOS and foreground then background on Android, with settings deep links.
- `getProviderState()` / `onProviderChange` report authorization level, precise vs approximate, and services enabled.
- Two deployments are supported. Managed devices (host app is device owner): the library detects policy-granted permissions and skips prompting; the host app, as DPC, grants permissions and battery exemption, and the library never calls `DevicePolicyManager`. Store-distributed devices: the full user-grant flow applies, including background-location, ActivityRecognition and notification prompts, OEM battery-kill guidance, and the Play Console background-location and foreground-service declarations plus App Store review for iOS.
- Foreground notification: configurable title, text and icon via a `notification` config.

## 8. JS API (v1 subset, Transistorsoft-compatible names)

From the 5.8.0 spec:
- Lifecycle: `ready`, `setConfig`, `reset`, `start`, `stop`, `changePace`, `getState`.
- Data: `getLocations`, `getCount`, `insertLocation`, `destroyLocations`, `destroyLocation`, `sync`, `getOdometer`, `setOdometer`, `getCurrentPosition`, `watchPosition`, `stopWatchPosition`.
- Geofences: `addGeofence(s)`, `removeGeofence(s)`, `getGeofences`, `getGeofence`, `geofenceExists`.
- Permissions/device: `requestPermission`, `getProviderState`, `getDeviceInfo`, `isPowerSaveMode`, `isIgnoringBatteryOptimizations`, `requestSettings`, `showSettings`.
- Background: `beginBackgroundTask`, `finish`, `registerHeadlessTask`.
- Events: `onLocation`, `onMotionChange`, `onHttp`, `onHeartbeat`, `onProviderChange`, `onGeofence`, `onGeofencesChange`, `onEnabledChange`, `onConnectivityChange`, `onPowerSaveChange`.

Additions: `getCapabilities()` returns `{ engine, geofenceLimit, activityRecognition, canRelaunchAfterKill, supportsHeadlessJs, managedDevice }`, so apps never assume the platforms are equal.

Deliberately not in v1: scheduler, `startSchedule`/`stopSchedule`, `onActivityChange` on the AOSP engine, Transistorsoft token methods, log email/upload.

## 9. Reliability criteria (acceptance tests)

| Criterion | Test |
|---|---|
| No recorded fix is lost | Kill the process between insert and upload, and with the server returning 500 or dropping; every `uuid` eventually arrives exactly once server-side after dedupe. |
| Auto-resume after reboot or kill | Android: reboot device and kill process, tracking resumes with no user action. iOS: relaunch via region exit resumes and flushes. |
| Detect and report own failure | Revoke permission, disable location, stop the service, or get no fix for N minutes: a failure event is emitted and a gap is logged. |
| Explicit iOS limits | `getCapabilities()` reports iOS limits; force-quit is documented as best-effort. |

## 10. Expo config plugin

Minimal plugin for Expo 57 prebuild/EAS: Android manifest permissions, `location` foreground-service type, service and boot receiver declarations; iOS `UIBackgroundModes: location`, usage description strings, motion usage string. The plugin is covered by snapshot tests.

## 11. Repository and testing

- Yarn workspaces: root library package (`src`, `android`, `ios`, `plugin`) plus `example/` app, bootstrapped from the create-react-native-library TurboModule template.
- Conformance suite (`conformance/`): the written spec (`README.md`), JSON fixtures, and a TypeScript reference model. The same fixtures run against the reference model (`yarn test:conformance`), the Kotlin core (`yarn test:kotlin`) and the Swift core (`yarn test:swift`); `yarn test:cores` runs all three (ADR-0003). The platform layers (stores, uploaders, engines, runtimes) have no automated tests yet (see `known-gaps.md`).
- Device matrix: Android with Play Services (not managed) and without (managed, device-owner), Android 11, 13 and 14+; iOS 15 and latest. Killed-app and reboot scenarios are manual-scripted in the example app.

## 12. Assumptions to confirm

1. The notification config mirrors Transistorsoft's `notification` fields.
2. Package name is a placeholder until publishing.
3. The buffer is stored unencrypted; retention is config-driven.
4. iOS stationary region radius and significant-change safety net are the standard pattern; exact radius tuned on devices.

## 13. Milestones

| # | Scope | State |
|---|---|---|
| 1 | Scaffold, conformance spec, fixtures, reference model | Done |
| 2 | Kotlin and Swift cores passing the fixtures | Done |
| 3 | Android runtime: AOSP engine and fallback chain, SQLite, uploader, foreground service, boot resume, TurboModule | Done for the AOSP engine only; the Fused engine is not built; real-motion, Doze and offline checks pending |
| 4 | iOS runtime: CoreLocation engine, stationary region, relaunch path, TurboModule | Built and simulator-verified; device background wake-up unverified |
| 5 | Geofences (both platforms), permission flow, capability flags | Core and spec done (17 fixtures, Kotlin and Swift). Android runtime done and emulator-verified. iOS runtime pending (stubs reject with `E_UNIMPLEMENTED`) |
| 6 | Expo config plugin, example app polish, integration into the two host apps | Not started |

## 14. Implementation notes: cores and Android (milestones 2 and 3)

- The Kotlin and Swift cores are pure and take the clock as input. They emit upload requests and receive results later; they never block on HTTP. They expose `nextDueAt` so the runtime can schedule the stop timeout and upload retries.
- Android scheduling uses two mechanisms together: a `Handler` timer for on-time wake-ups while the process is awake, and an inexact `AlarmManager.setAndAllowWhileIdle` as the backstop while the CPU sleeps. The alarm alone fired the stop timeout about 45 s late on a Samsung A06 (Android 16); the handler timer alone would not fire during deep sleep.
- Verified on the Samsung SM-A065F (Android 16) with the AOSP engine and mock GPS: start and foreground service (`location` type), distance filter, stop timeout to stationary with GPS released and the significant-motion trigger armed, upload with failure and exponential backoff (10 s, 20 s, 40 s) then drain, and recovery after `kill -9` (service restarted, state and config restored, trigger re-armed).
- Verified on an emulator (API 36, device owner through the example app's `KioskDeviceReceiver`, kiosk restrictions applied, Play Services, GSF and Play Store disabled): start, record, upload, stop timeout, boot resume after reboot, and the fallback chain. The emulator has no significant-motion sensor, which exposed the original dead end (the core went stationary with no way to wake) and led to the chain; the accelerometer stage resumed from a shaken virtual accelerometer and the GPS-poll stage from a 150 m move.

### Kiosk test setup (example app)

The example app mirrors NSS's kiosk setup: a `DeviceAdminReceiver`, `device_owner.xml`, a `Kiosk` native module applying the NSS restriction set when it is device owner, and the HOME category on `MainActivity`. The manifest sets `android:testOnly="true"` so the admin can be removed.

- Install with `adb install -r -t`, then `ANDROID_SERIAL=<serial> yarn example set-owner`.
- `set-device-owner` is refused when the device has accounts. On a disposable emulator, set `device_provisioned` and `user_setup_complete` to 0 around the command, then back to 1. Do not do this on a personal phone.
- Undo with `ANDROID_SERIAL=<serial> yarn example remove-admin`. Restrictions set by the app clear with it.

## 14b. Implementation notes: geofences, permissions and capabilities (milestone 5, Android)

- Geofence rules 17 to 22 in `conformance/README.md` are implemented in the Kotlin and Swift cores and pass the shared fixtures. All geofences are evaluated by the core against fixes (while moving), against periodic `geofenceCheck` readings (while stationary), and against native region signals (`geofenceTransition`, for iOS). The registry and per-geofence inside state are persisted so a restart does not repeat an `ENTER`.
- Android: a `geofence` SQLite table (database version 2), and the AOSP engine takes a one-shot fix every `geofenceCheckInterval` seconds (default 300) while stationary and geofences exist. The API limit is 20. Verified on the emulator: `ENTER` and `EXIT` fired from stationary checks with the tracking state staying `STATIONARY`.
- `getProviderState()` and `onProviderChange` report location services, authorization (`always`, `whenInUse`, `denied`), accuracy and the individual Android permissions. `requestPermission()` asks in order for foreground location, notifications, activity recognition and background location, skipping anything already granted (so policy-granted managed devices see no prompts). `getCapabilities()` reports engine, geofence limit, relaunch and managed-device flags.
- **iOS limit decision (open):** iOS monitors geofences natively and the stationary region (ADR-0004) uses one of the 20 region slots, so the iOS limit will be 19 and `getCapabilities().geofenceLimit` will say so. This departs from the earlier "20 on both platforms" decision and needs your agreement.
- **Bug found by testing:** revoking the location permission while the app ran made Android restart the foreground service, and `startForeground` threw a `SecurityException` that crashed the app. The service, the boot receiver and `start()` now check the permission first, report a `failure` (event and `getState().failure`) and stop instead of crashing. Verified on the emulator: no crash, failure reported, app alive.

## 15. Implementation notes: iOS (milestone 4)

- The iOS runtime (`ios/Runtime/`) mirrors the Android one around the same Swift core: `CoreLocationEngine`, a SQLite `FixStore` (sqlite3 C API), `UserDefaults` state and config stores, a `URLSession` uploader wrapped in a background task, an `NWPathMonitor` connectivity listener, and a main-queue timer driven by `nextDueAt`. Everything runs on the main queue because `CLLocationManager` needs a run loop and the work per event is tiny.
- Moving: standard updates with background updates on and `pausesLocationUpdatesAutomatically` off. Stationary: see section 4 and ADR-0004.
- Relaunch: a `+load` observer on `UIApplicationDidFinishLaunching` calls `BgGeoRuntime.launchIfNeeded()`, so a launch caused by a location event restores the persisted state and re-arms the engine natively, without waiting for JS.
- A minimal permission prompt is in the engine (When In Use, then Always, retrying the pending start). The full flow is milestone 5.
- Example app: the template crashed on iOS 27 with `NoSceneLifecycleAdoption`, so the example adopts the scene lifecycle (`SceneDelegate`, `UIApplicationSceneManifest`). The `Kiosk` module is Android-only and guarded by platform.
- Simulator (iPhone 17, iOS 27): start, record, distance filter, upload, stop timeout to stationary, and resume when a continuous route left the region. Teleporting with `simctl location set` does not wake the app.
- Device (iPhone 14 Pro Max, iOS 27): real GPS fixes uploaded, Always authorization granted, the region registered, and the app went stationary on its own. Two background wake-up attempts were inconclusive. The first exposed a real bug: a region left over from an earlier session was trusted on relaunch while the device was already outside it, so no exit event could ever fire. The second never left the region (a one-shot check measured 31 m from the centre). Fixes that came out of it: verify an existing region on arming, and report `monitoringDidFailFor`. A valid straight-line walk is still outstanding (`known-gaps.md`).
