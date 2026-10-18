---
status: accepted
---
# Both Android engines are first-class: Fused where Play Services exists, AOSP where it does not

The host apps span two deployments. Admin-owned apps run on device-owner-managed Android devices where Google Play Services is disabled, so there is no Fused provider, ActivityRecognition or native geofencing. Store-distributed apps (smart-attendance) run on ordinary Play devices where Fused is the better engine. Android location therefore sits behind an engine interface with a Fused and an AOSP implementation, chosen at runtime (`engine: 'auto'` picks Fused when Play Services is usable), and neither is a fallback for the other: each is fully tested.

The AOSP engine detects stopping in the core from displacement over the stop timeout, and detects resumed movement through a fallback chain, using the first source that is available: `TYPE_SIGNIFICANT_MOTION`, then `TYPE_STEP_DETECTOR` (needs the ACTIVITY_RECOGNITION permission on Android 10+), then accelerometer variance over 5 s windows, then a periodic low-power GPS poll compared against the last known position. Geofences are evaluated by us against fixes.

## Considered Options

- GMS primary with a best-effort non-GMS fallback: rejected because non-GMS is a production deployment, not an edge case.
- AOSP only: rejected because it gives up ActivityRecognition labels and native geofencing on devices that have them.
- GPS-only duty cycling on AOSP: rejected for battery cost and late detection of resumed movement.

## Consequences

- The fallback chain lives in the engine, not the core, so the core's rules and conformance fixtures are unchanged; the engine only emits `motionSignal`.
- The chosen source is reported through the `motionsource` event and `getState().motionSource` so apps and tests can see which stage a device is on. Later stages cost more battery and detect movement later; `useMotionSensors: false` forces the GPS poll and `motionPollInterval` (seconds, default 300) sets its period.
- Sensors that are not wake-up variants deliver only while the CPU is awake, and the poll runs on an in-process timer, so detection on a sleeping device can be late.
- No activity labels on the AOSP engine, so `onActivityChange` is unsupported there (exposed via capability flags).
- While Stationary, GPS is off on the AOSP engine, so geofence enter/exit is checked periodically (minutes of latency accepted) and on significant motion.
- Both engines feed the same core, so the conformance fixtures apply to both; engine-specific inputs differ only in which signal produces `motionSignal`.
- The test matrix needs a Play device and a no-Play-Services device.
