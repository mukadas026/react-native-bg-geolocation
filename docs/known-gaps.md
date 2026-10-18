# Known gaps

What is unverified, unspecified or unbuilt as of the end of milestone 5. Milestone 6 (Expo plugin, host-app integration) is not finished. Each item says what would close it. Items marked **device** need physical hardware.

## 1. Unverified behavior

### iOS

| Claim | State | Closes with |
|---|---|---|
| A suspended app is woken by a region exit and resumes tracking | **Unverified.** Two device runs were inconclusive: the first used a stale region (since fixed), the second never left the 100 m region (the one-shot check measured 31 m from the centre). | **device**: stand still until the region arms, lock the phone, walk 300 m or more in a straight line with the console attached. |
| A terminated or force-quit app is relaunched by a region exit or significant-change | **Unverified.** Apple documents relaunch for location events but force-quit behavior is inconsistent; the design only promises best effort. | **device**: same walk after force-quit; record the result as a documented limit either way. |
| Significant-change safety net delivers in time | **Unverified.** Registration is confirmed by logs, delivery is not. | **device**, longer travel (kilometres). |
| Always-authorization upgrade, downgrade and the background-location indicator | Partly seen: Always was granted on the phone. Downgrade handling is untested. | **device**: revoke to While Using mid-session and check the reported failure. |
| Battery effect of the stationary-region approach | Unmeasured. | **device**: multi-hour comparison. |
| Native region monitoring for geofences | **Failed in the simulator** with `kCLErrorDomain` error 5 (`kCLErrorRegionMonitoringFailure`), reported through the new `monitoringDidFailFor` handling. Geofences still fired through the core's fix evaluation while moving. Whether native regions work (and wake the app while stationary) is unknown. | **device**: add a geofence, stand still until stationary, then cross the boundary. |
| iOS permission request (`requestPermission`) | Unverified. The simulator cannot be tapped from the command line, so the system dialogs were never driven; only the already-granted path ran. | **device**: fresh install, tap Permission, accept both prompts. |

Verified in the iPhone 17 simulator (iOS 27) only: start, record, distance filter, upload, stop timeout, and resume when a continuous simulated route leaves the region. Teleporting with `simctl location set` does not wake the app.

### Android

| Claim | State | Closes with |
|---|---|---|
| Resume from stationary on a real significant-motion event | **Unverified.** The sensor was confirmed armed on the Samsung A06; a real motion event was never produced. | **device**: shake or walk with the phone while stationary. |
| Doze and long idle behavior | Unverified. Timers (`Handler`) only fire while the CPU is awake; the alarm backstop is inexact. | **device**: overnight run, compare expected and actual stop and poll times. |
| Offline buffering with real connectivity changes | Unverified. Tested only via a server returning 500. | **device**: airplane mode on and off. |
| Step-detector fallback stage | **Untested.** The Samsung has significant motion (wins) and the emulator has no step sensor. | A device with a step detector but no significant motion, or a sensor-injecting emulator. |
| Boot resume on a real device | Verified on the emulator only. | **device**: reboot the Samsung (needs an unlock). |
| Operation with Play Services disabled | Verified on the emulator only. | **device**: disable Play Services on a spare device. |
| Battery cost of each fallback stage | Unmeasured. | **device**: per-stage overnight drain. |
| Permission dialogs from `requestPermission()` | **Unverified.** Only the missing-permission failure path was exercised; the sequential dialogs (foreground, notifications, activity recognition, background) were not driven end to end. | Tap through them on a device, including Android 11+ where background location goes through Settings. |
| Geofences on a real device | Verified on the emulator only, using mock GPS and the stationary check path. | **device**: walk across a real boundary. |
| `onProviderChange` | Wired to the providers-changed broadcast; never observed firing. | Toggle location services on a device. |

## 2. Code gaps

- **The Fused engine is not implemented.** ADR-0002 makes both Android engines first-class, but only `AospEngine` exists. Play-Services devices (such as the store-distributed smart-attendance app) currently run on the AOSP engine, which works there but forgoes ActivityRecognition labels, native geofencing and Fused battery behavior. `engine: 'auto'` and `engine: 'aosp'` are not wired up yet.
- **No automated tests for the platform layers.** Only the pure cores (Kotlin, Swift) and the TypeScript reference model run in CI-able suites. The SQLite stores, uploaders, engines and runtimes have none.
- **No fallback when iOS native geofence regions fail.** The engine reports `monitoringDidFailFor` as an error, but while stationary there is then no other source of geofence events (Android polls; iOS relies on regions). A failed region should trigger a periodic one-shot check like Android's.
- **iOS geofence limit is 19, not 20.** One of the 20 region slots is the stationary region; `getCapabilities().geofenceLimit` reports it. This departs from the original "20 on both platforms" decision and has not been explicitly approved.
- **Dev-only: the iOS example can crash at launch** with `No script URL provided` when React Native's packager check fails in the simulator (it did here even though Metro answered). Setting `RCT_jsLocation` to `127.0.0.1:8081` (`xcrun simctl spawn <udid> defaults write bggeolocation.example RCT_jsLocation 127.0.0.1:8081`) fixed it. The cause was not found.
- **Android service restart from the background.** The alarm receiver restarts the runtime but cannot start the foreground service; recovery relies on `START_STICKY` and the boot receiver.
- **`ready()` rebuilds the core.** Calling it again, or changing config, resets the stop-detection anchor and last-recorded fix.
- **Config changes are not partial.** There is no `setConfig`; `ready()` replaces the whole config.
- **iOS arms twice at launch** (relaunch hook and `ready()`), which is harmless but redundant. Diagnostic `NSLog` lines are still in `CoreLocationEngine`.
- **Timers while suspended.** On iOS the main-queue timer does not fire while the app is suspended, so upload retries and the stop timeout wait for the next wake-up. Android's alarm backstop is inexact.
- **Android notification** supports title and text only; the small icon is a system placeholder.
- **Distance filter is applied twice.** The engine passes `distanceFilter` to the platform and the core filters again; harmless but untested for edge cases.
- **No upload authentication handling.** 401 and 403 are treated like any other failure and retried with backoff; the design calls for pausing sync.
- **Package name, README and CI are template-level.** The package name is a placeholder; CI does not run the core test suites.

## 3. Specification gaps

Not yet in `conformance/README.md`, so no fixtures exist: heartbeat, odometer, accuracy filtering, HTTP 401/403 pausing, geofence dwell, geofence-only tracking, and speed-based stop detection (ADR-0002 originally mentioned speed; the core uses displacement only). Geofences are specified (rules 17 to 22).

## 4. API gaps (versus the planned v1 surface in `design.md` section 8)

Implemented: `ready`, `start`, `stop`, `changePace`, `getState`, `getCount`, `getLocations`, `destroyLocations`, and the events `onLocation`, `onMotionChange`, `onEnabledChange`, `onBufferOverflow`, `onMotionSource`, `onError`.

Also implemented on both platforms: `addGeofence`, `addGeofences`, `removeGeofence`, `removeGeofences`, `getGeofences`, `getGeofence`, `geofenceExists`, `requestPermission`, `getProviderState`, `getCapabilities`, and the events `onGeofence` and `onProviderChange`. `onGeofencesChange` is deferred.

Missing everywhere: `setConfig`, `reset`, `getCurrentPosition`, `watchPosition`, `stopWatchPosition`, `insertLocation`, `destroyLocation`, `sync`, odometer, `getDeviceInfo`, `isPowerSaveMode`, `isIgnoringBatteryOptimizations`, `requestSettings`, `showSettings`, `beginBackgroundTask`/`finish`, `registerHeadlessTask`, `onHttp`, `onHeartbeat`, `onConnectivityChange`, `onPowerSaveChange`. Optional best-effort headless JS is not implemented.

## 5. Process gaps

- Milestone 6 (Expo config plugin, integration into the two host apps) is not finished. Integrating into the host apps edits repositories outside this one, so it needs your go-ahead.
- The host apps still use their own location code and `react-native-permissions` for location, which must be removed on adoption.
- Example app: title and state text are dark-on-black in dark mode.
