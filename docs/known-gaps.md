# Known gaps

What is unverified, unspecified or unbuilt as of milestone 4. Each item says what would close it. Items marked **device** need physical hardware.

## 1. Unverified behavior

### iOS

| Claim | State | Closes with |
|---|---|---|
| A suspended app is woken by a region exit and resumes tracking | **Unverified.** Two device runs were inconclusive: the first used a stale region (since fixed), the second never left the 100 m region (the one-shot check measured 31 m from the centre). | **device**: stand still until the region arms, lock the phone, walk 300 m or more in a straight line with the console attached. |
| A terminated or force-quit app is relaunched by a region exit or significant-change | **Unverified.** Apple documents relaunch for location events but force-quit behavior is inconsistent; the design only promises best effort. | **device**: same walk after force-quit; record the result as a documented limit either way. |
| Significant-change safety net delivers in time | **Unverified.** Registration is confirmed by logs, delivery is not. | **device**, longer travel (kilometres). |
| Always-authorization upgrade, downgrade and the background-location indicator | Partly seen: Always was granted on the phone. Downgrade handling is untested. | **device**: revoke to While Using mid-session and check the reported failure. |
| Battery effect of the stationary-region approach | Unmeasured. | **device**: multi-hour comparison. |

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

## 2. Code gaps

- **No automated tests for the platform layers.** Only the pure cores (Kotlin, Swift) and the TypeScript reference model run in CI-able suites. The SQLite stores, uploaders, engines and runtimes have none.
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

Not yet in `conformance/README.md`, so no fixtures exist: geofences, heartbeat, odometer, accuracy filtering, HTTP 401/403 pausing, speed-based stop detection (ADR-0002 originally mentioned speed; the core uses displacement only), and periodic low-power checks while stationary.

## 4. API gaps (versus the planned v1 surface in `design.md` section 8)

Implemented: `ready`, `start`, `stop`, `changePace`, `getState`, `getCount`, `getLocations`, `destroyLocations`, and the events `onLocation`, `onMotionChange`, `onEnabledChange`, `onBufferOverflow`, `onMotionSource`, `onError`.

Missing: `setConfig`, `reset`, `getCurrentPosition`, `watchPosition`, `stopWatchPosition`, `insertLocation`, `destroyLocation`, `sync`, odometer, the geofence methods and events, `requestPermission`, `getProviderState`, `onProviderChange`, `getCapabilities`, `getDeviceInfo`, `isPowerSaveMode`, `isIgnoringBatteryOptimizations`, `requestSettings`, `showSettings`, `beginBackgroundTask`/`finish`, `registerHeadlessTask`, `onHttp`, `onHeartbeat`, `onConnectivityChange`, `onPowerSaveChange`. Optional best-effort headless JS is not implemented.

## 5. Process gaps

- Milestones 5 (geofences, permission flow, capability flags) and 6 (Expo config plugin, integration into the two host apps) are not started.
- The host apps still use their own location code and `react-native-permissions` for location, which must be removed on adoption.
- Example app: title and state text are dark-on-black in dark mode.
