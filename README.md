# @mukadasm/react-native-bg-geolocation

[![npm (beta)](https://img.shields.io/npm/v/@mukadasm/react-native-bg-geolocation/beta?label=npm%20beta)](https://www.npmjs.com/package/@mukadasm/react-native-bg-geolocation)
[![license](https://img.shields.io/npm/l/@mukadasm/react-native-bg-geolocation)](LICENSE)

**Links:** [npm](https://www.npmjs.com/package/@mukadasm/react-native-bg-geolocation) · [GitHub](https://github.com/mukadas026/react-native-bg-geolocation) · [Issues](https://github.com/mukadas026/react-native-bg-geolocation/issues) · [Docs](docs/getting-started.md)

Background geolocation for React Native, built to run on Android with or without Google Play Services and on iOS. It records location in the background, decides whether the device is moving or still, buffers fixes in a native SQLite store, and uploads them to your server, all without needing JavaScript to be alive.

**Status: beta (`0.0.1-beta.0`).** Core tracking, buffered upload, geofences, permissions and the Expo plugin are built on both platforms, but several behaviors are only verified on emulators and simulators. Read [`docs/known-gaps.md`](docs/known-gaps.md) before depending on it.

## Install

```sh
npm install @mukadasm/react-native-bg-geolocation@beta
```

Then follow [Getting started](docs/getting-started.md) for platform setup, and see the [API reference](docs/api.md) for every method, event and option.

## How it works

- **Native core per platform** (Kotlin, Swift) holds the tracking state machine (stopped, moving, stationary), the buffer rules and the upload queue. Both run the same JSON conformance fixtures, so they cannot drift apart silently ([ADR-0003](docs/adr/0003-two-native-cores-with-conformance-fixtures.md)).
- **Engines** feed the core location and motion signals: an AOSP engine for devices without Play Services (with a sensor fallback chain), and CoreLocation on iOS (stationary region plus significant-change) ([ADR-0002](docs/adr/0002-both-android-engines-are-first-class.md), [ADR-0004](docs/adr/0004-ios-stationary-region-and-significant-change.md)).
- **Native owns persistence and upload** so a killed or relaunched app keeps recording ([ADR-0001](docs/adr/0001-native-owns-persistence-and-sync.md)).

## Requirements

React Native 0.76+ with the New Architecture, Android minSdk 24, iOS 15+.

## Usage

```ts
import BackgroundGeolocation from '@mukadasm/react-native-bg-geolocation';

BackgroundGeolocation.onLocation((location) => console.log(location.coords));
BackgroundGeolocation.onMotionChange(({ isMoving }) => console.log(isMoving));

await BackgroundGeolocation.ready({
  distanceFilter: 10,
  stopTimeout: 5, // minutes
  url: 'https://example.com/locations',
});
await BackgroundGeolocation.start();
```

Uploads are `POST` requests of `{ "location": [ { "uuid", "timestamp", "coords": { ... } } ] }`, retried with exponential backoff; dedupe on `uuid` server-side. The full planned API is in [`docs/design.md`](docs/design.md).

## Expo

Add the config plugin to your app config:

```json
{ "expo": { "plugins": [["@mukadasm/react-native-bg-geolocation", { "iosAlwaysUsageDescription": "..." }]] } }
```

It adds the iOS `location` background mode and the two location usage descriptions (keeping strings your app already set unless you pass them as options). Android needs nothing from the plugin: the library manifest contributes the permissions, foreground service and receivers. Verified with `expo prebuild` on a throwaway Expo SDK 58 project; needs a development build (not Expo Go).

## Repository layout

| Path | Contents |
|---|---|
| `src/` | TypeScript facade and TurboModule spec |
| `android/` | Kotlin core (`core/`), runtime, engines, TurboModule; `core-tests/` is a standalone JVM test project |
| `ios/` | Swift core (`Core/`, a SwiftPM package), runtime (`Runtime/`), ObjC++ TurboModule shim |
| `conformance/` | Written spec, JSON fixtures and a TypeScript reference model |
| `plugin/` | Expo config plugin (`app.plugin.js` loads `plugin/dist`) |
| `example/` | Test app, including an Android device-owner kiosk setup |
| `docs/` | Design, ADRs, known gaps, host-app integration plan |

## Development

```sh
yarn install
yarn test:cores        # reference model + Kotlin core + Swift core against the same fixtures
yarn test:plugin       # Expo config plugin
yarn lint && yarn typecheck
yarn example start     # Metro for the example app
```

Android needs the SDK and JDK 17+; iOS needs Xcode and `pod install` in `example/ios`. The example's kiosk and admin setup is described in [`docs/design.md`](docs/design.md) section 14.

## License

Apache-2.0
