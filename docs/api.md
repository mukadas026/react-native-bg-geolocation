# API reference

```ts
import BackgroundGeolocation from '@mukadasm/react-native-bg-geolocation';
```

Everything is on the default export. All methods return promises. Event methods return a subscription: `{ remove(): void }`.

## Lifecycle

### `ready(config): Promise<State>`

Applies and persists the configuration, then returns the current state. Call it once per app launch. Calling it again replaces the whole config and rebuilds the tracking core, which resets stop detection (it does not stop or clear anything stored).

### `start(): Promise<State>`

Starts tracking: state becomes `moving`. On Android this also starts the foreground service. Rejects with code `E_START` if location permission is missing (Android).

### `stop(): Promise<State>`

Stops tracking and the engine. The buffer is kept. While stopped no fixes are recorded and no geofence events fire.

### `changePace(isMoving: boolean): Promise<State>`

Forces the state: `true` resumes from `stationary` to `moving`, `false` moves from `moving` to `stationary`. Ignored when stopped or when already in that state.

### `getState(): Promise<State>`

```ts
type State = {
  enabled: boolean;
  trackingState: 'stopped' | 'moving' | 'stationary';
  isMoving: boolean;
  count: number; // fixes waiting in the buffer
  motionSource?: 'significant-motion' | 'step-detector' | 'accelerometer' | 'gps-poll' | 'stationary-region' | null;
  failure?: string | null; // last reported failure (Android)
};
```

## Stored locations

### `getLocations(): Promise<Location[]>`

Fixes still in the buffer, oldest first.

```ts
type Location = {
  uuid: string;
  timestamp?: number; // milliseconds since the epoch
  coords: { latitude: number; longitude: number; accuracy?: number; speed?: number; heading?: number; altitude?: number };
};
```

### `getCount(): Promise<number>`

Number of buffered fixes.

### `destroyLocations(): Promise<boolean>`

Deletes every buffered fix, **including ones not yet uploaded**.

## Geofences

```ts
type Geofence = {
  identifier: string;
  latitude: number;
  longitude: number;
  radius: number; // meters, must be greater than 0
  notifyOnEntry?: boolean; // default true
  notifyOnExit?: boolean; // default true
};
```

- Limit: 20 on Android, 19 on iOS (`getCapabilities().geofenceLimit`). iOS treats radii below about 100 m as imprecise.
- Adding an existing `identifier` replaces that geofence and forgets whether the device was inside it.
- A geofence the device is already inside produces `ENTER` on the first reading.
- Geofences persist across restarts but only produce events while tracking is started.

| Method | Resolves | Rejects |
|---|---|---|
| `addGeofence(geofence)` / `addGeofences(geofences)` | `true` | `E_GEOFENCE` (limit reached or invalid radius), `E_INVALID_GEOFENCE` (malformed input, Android) |
| `removeGeofence(identifier)` / `removeGeofences(identifiers)` | `true` (also when the identifier is unknown) | |
| `getGeofences()` | `Geofence[]` in registration order | |
| `getGeofence(identifier)` | `Geofence` | `E_NOT_FOUND` |
| `geofenceExists(identifier)` | `boolean` | |

## Permissions and device state

### `requestPermission(): Promise<ProviderState>`

Asks for whatever is still missing and returns the resulting state. Anything already granted is skipped, so on a device where an administrator has granted the permissions by policy there are no prompts.

- **Android**, in order: foreground location, notifications (Android 13+), activity recognition (Android 10+), then background location (Android 10+). It must be called while an activity is visible, otherwise it rejects with `E_NO_ACTIVITY`. On Android 11 and newer, background location is granted through a system settings screen.
- **iOS**: When In Use, then Always. The Always prompt may not appear immediately; the call returns the state after a short wait.

### `getProviderState(): Promise<ProviderState>`

```ts
type ProviderState = {
  enabled: boolean; // location services are on
  authorization: 'always' | 'whenInUse' | 'denied' | 'notDetermined'; // notDetermined: iOS only
  accuracy: 'full' | 'reduced';
  gps?: boolean; // Android
  network?: boolean; // Android
  permissions?: { fineLocation; coarseLocation; backgroundLocation; notifications; activityRecognition }; // Android, booleans
};
```

### `getCapabilities(): Promise<Capabilities>`

```ts
type Capabilities = {
  platform: 'android' | 'ios';
  engine: 'aosp' | 'corelocation';
  geofenceLimit: number;
  geofenceMinRadius?: number; // iOS
  activityRecognition: boolean;
  canRelaunchAfterKill: boolean; // best effort on iOS
  supportsHeadlessJs: boolean; // false in this release
  managedDevice: boolean; // Android: the app is device owner
  motionSource?: string | null;
};
```

Use these instead of assuming the two platforms behave the same.

## Events

| Subscribe | Payload | Fires when |
|---|---|---|
| `onLocation(fn)` | `Location` | A fix is recorded (not every raw reading) |
| `onMotionChange(fn)` | `{ isMoving }` | The state moves between moving and stationary |
| `onEnabledChange(fn)` | `{ enabled }` | Tracking starts or stops |
| `onGeofence(fn)` | `{ identifier, action: 'ENTER' \| 'EXIT' }` | A geofence boundary is crossed |
| `onProviderChange(fn)` | `ProviderState` | Location services or authorization change |
| `onBufferOverflow(fn)` | `{ dropped: string[] }` | `maxRecordsToPersist` was exceeded and the oldest fixes (listed by `uuid`) were dropped |
| `onMotionSource(fn)` | `{ source }` | The mechanism used to detect movement changes |
| `onError(fn)` | `{ message }` | A failure such as missing permission or a region that could not be monitored |

Events are delivered only while your JavaScript is running. Recording and uploading continue regardless; use `getLocations()` and `getState()` to catch up.

## Configuration

Pass to `ready()`. Every option is optional.

| Option | Default | Platforms | Meaning |
|---|---|---|---|
| `url` | none | both | Where fixes are `POST`ed. Without it nothing is uploaded |
| `headers` | `{}` | both | Extra request headers, for example `Authorization` |
| `autoSync` | `true` | both | Upload automatically as fixes are recorded (needs `url`) |
| `maxBatchSize` | `100` | both | Fixes per upload request |
| `syncRetryBaseDelay` | `10` | both | Seconds before the first retry; doubles on each failure |
| `syncRetryMaxDelay` | `300` | both | Cap on the retry delay, in seconds |
| `maxRecordsToPersist` | `-1` | both | Buffer size limit (`-1` = unlimited). At the limit the oldest fixes are dropped and `onBufferOverflow` fires |
| `distanceFilter` | `10` | both | Minimum meters from the last recorded fix before recording another |
| `stopTimeout` | `5` | both | Minutes without leaving `stationaryRadius` before tracking pauses |
| `stationaryRadius` | `25` | both | Meters. On iOS the monitored region is at least 100 m |
| `notificationTitle` | `"Tracking location"` | Android | Foreground notification title |
| `notificationText` | `"Location tracking is active"` | Android | Foreground notification text |
| `motionPollInterval` | `300` | Android | Seconds between GPS checks when no motion sensor is available |
| `useMotionSensors` | `true` | Android | `false` skips the sensors and uses only the periodic GPS check |
| `geofenceCheckInterval` | `300` | Android | Seconds between geofence checks while stationary |

## Not in this release

`setConfig`, `reset`, `getCurrentPosition`, `watchPosition`, `insertLocation`, `sync`, odometer, headless JavaScript, scheduling, and geofence dwell are not implemented. See [known-gaps.md](known-gaps.md).
