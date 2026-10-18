# Getting started

> **Beta.** This package is a prerelease. Read [known-gaps.md](known-gaps.md) before depending on it: several behaviors are verified only on emulators or simulators.

## Requirements

- React Native 0.76 or newer with the **New Architecture** enabled. The old bridge is not supported.
- Android: minSdk 24. Works with or without Google Play Services.
- iOS 15 or newer.
- A development build. It cannot run in Expo Go because it contains native code.

## Install

```sh
npm install @mukadasm/react-native-bg-geolocation@beta
# or: yarn add @mukadasm/react-native-bg-geolocation@beta
```

The `@beta` tag is required while only prereleases are published.

### iOS

```sh
cd ios && pod install
```

Add these to `Info.plist` (the Expo plugin below does this for you):

| Key | Value |
|---|---|
| `UIBackgroundModes` | an array containing `location` |
| `NSLocationAlwaysAndWhenInUseUsageDescription` | why the app tracks in the background |
| `NSLocationWhenInUseUsageDescription` | why the app uses location |

The library asks for **When In Use** first and then **Always**. Background wake-ups after the system suspends the app need **Always**.

### Android

Nothing to configure by hand. The library's manifest declares, and Gradle merges into your app:

- permissions: fine, coarse and background location, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `ACTIVITY_RECOGNITION`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`, `ACCESS_NETWORK_STATE`;
- a `location` foreground service (it shows a persistent notification while tracking);
- a boot receiver that resumes tracking after a reboot or app update, and an alarm receiver.

If you publish to Google Play you must complete the background-location and foreground-service (location) declarations in the Play Console.

### Expo

```json
{
  "expo": {
    "plugins": [
      ["@mukadasm/react-native-bg-geolocation", { "iosAlwaysUsageDescription": "Tracks where this device has been." }]
    ]
  }
}
```

Options: `iosAlwaysUsageDescription` and `iosWhenInUseUsageDescription`. Strings already in your config are kept unless you pass these. Then run `npx expo prebuild`.

## Minimal use

```ts
import BackgroundGeolocation from '@mukadasm/react-native-bg-geolocation';

// 1. Subscribe before starting.
const subscriptions = [
  BackgroundGeolocation.onLocation((location) => console.log(location.coords)),
  BackgroundGeolocation.onMotionChange(({ isMoving }) => console.log('moving?', isMoving)),
  BackgroundGeolocation.onError(({ message }) => console.warn(message)),
];

// 2. Configure once per launch.
await BackgroundGeolocation.ready({
  url: 'https://api.example.com/locations',
  headers: { Authorization: 'Bearer <token>' },
  distanceFilter: 10, // meters
  stopTimeout: 5, // minutes of stillness before tracking pauses
});

// 3. Ask for permission (Android needs a visible screen).
const provider = await BackgroundGeolocation.requestPermission();
if (provider.authorization === 'denied') return;

// 4. Start.
await BackgroundGeolocation.start();

// Later:
await BackgroundGeolocation.stop();
subscriptions.forEach((subscription) => subscription.remove());
```

Call `ready()` once per app launch, not per screen: calling it again rebuilds the tracking core and resets stop detection.

## How tracking behaves

- **Moving**: location updates run continuously and each fix farther than `distanceFilter` from the last recorded one is stored.
- **Stationary**: after `stopTimeout` minutes without moving beyond `stationaryRadius`, continuous location stops to save battery. Movement is detected by the platform (Android: motion sensors with fallbacks; iOS: leaving a monitored region) and tracking resumes.
- **Storing and uploading** happen in native code, so they continue when your JavaScript is not running. Fixes are written to an on-device SQLite buffer first and removed only after your server answers `2xx`.
- **Server contract**: your `url` receives `POST` requests with this body, retried with exponential backoff on any non-2xx or network error:

```json
{
  "location": [
    {
      "uuid": "d3b0…",
      "timestamp": "2026-10-18T20:36:23.441Z",
      "coords": { "latitude": 5.6037, "longitude": -0.187, "accuracy": 5, "speed": 1.2, "heading": 90, "altitude": 63 }
    }
  ]
}
```

  Optional `coords` fields are omitted when unknown. A fix can be delivered more than once, so **deduplicate on `uuid`** and answer `2xx` only after storing.
- Without a `url`, nothing is uploaded; fixes stay in the buffer and `getLocations()` returns them.

## Platform differences

| | Android | iOS |
|---|---|---|
| Background mechanism | Foreground service with a notification | Background location mode (status-bar indicator) |
| Resume from stationary | Significant motion, then step detector, then accelerometer, then a periodic GPS check (`getState().motionSource` says which) | Exit from a monitored region, plus significant-change monitoring |
| Geofence limit | 20 | 19 (one region slot is used for stationary detection) |
| After reboot | Resumes automatically | Resumes when iOS next wakes the app |
| After the user force-quits | Not resumed until the app is opened | Best effort only; do not rely on it |
| Notification options | `notificationTitle`, `notificationText` | not applicable |

`getCapabilities()` reports the limits for the current device.

## Troubleshooting

- **`start()` rejects with `E_START`** (Android): location permission is missing. Call `requestPermission()` first. A missing permission while tracking is reported through `onError` and `getState().failure` instead of crashing.
- **No `onLocation` events while stationary**: expected. Fixes are only recorded while moving.
- **Uploads never arrive**: check `getState().count` (buffered fixes), that `url` is reachable from the device, and that your server returns `2xx`. Plain `http://` URLs need cleartext traffic allowed in your app (Android) or an App Transport Security exception (iOS).
- **Tracking stops on some Android phones**: aggressive battery managers (several manufacturers) can kill background services. Ask the user to exempt the app from battery optimization.
- **Android: `onGeofence` or movement detection is late while the phone sleeps**: checks run on timers that the system may delay in Doze.
