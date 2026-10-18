# Background Geolocation

A React Native library that records a device's location in the background, decides whether the device is moving or still, buffers fixes, and syncs them, on Android with or without Google Play Services and on iOS.

## Language

**Tracking state**:
The library's current mode: stopped, moving, or stationary.
_Avoid_: Mode, status

**Moving**:
Tracking state in which location fixes are being recorded continuously.
_Avoid_: Active, tracking

**Stationary**:
Tracking state in which the device has stopped for longer than the stop timeout and continuous location is switched off until movement resumes.
_Avoid_: Idle, paused

**Engine**:
The platform-specific component that supplies location fixes and movement signals to the tracking state machine.
_Avoid_: Provider, backend

**Fused engine**:
Android engine built on Google Play Services location, activity recognition and geofencing, used on devices where Play Services is available.
_Avoid_: GMS provider

**AOSP engine**:
Android engine built only on platform APIs, used on devices without usable Google Play Services. It detects movement from the significant-motion sensor and GPS speed.
_Avoid_: Fallback engine, legacy engine

**Motion signal**:
An engine's report that a stationary device has started moving, which the core turns into the Moving state.
_Avoid_: Wake event, movement trigger

**Motion source**:
The mechanism an engine is currently using to produce motion signals, such as significant motion, step detector, accelerometer, GPS poll, or stationary region.
_Avoid_: Fallback, detector

**Stationary region**:
The circular region iOS monitors around where the device settled, whose exit is the motion signal.
_Avoid_: Geofence, anchor

**Fix**:
A single recorded location reading, identified by a uuid that servers can dedupe on.
_Avoid_: Point, location record

**Buffer**:
The on-device SQLite store holding fixes until the server confirms receipt.
_Avoid_: Queue, cache, database

**Sync batch**:
A group of buffered fixes posted to the configured URL in one request and deleted from the buffer only on a 2xx response.
_Avoid_: Upload, payload

**Geofence**:
A circular region whose entry and exit are reported as events, limited to 20 per device.
_Avoid_: Region, zone

**Managed device**:
A device on which the host app is the device owner and can grant itself permissions and exempt itself from battery optimization by policy.
_Avoid_: Enterprise device, MDM device
