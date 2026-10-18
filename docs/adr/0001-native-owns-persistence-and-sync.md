---
status: accepted
---
# Native owns the buffer and HTTP sync; JS only configures and subscribes

iOS relaunches a terminated app for only a short window in which the JS runtime may not boot, and Android processes can be killed at any time. Fixes are therefore written to the SQLite buffer and uploaded by native code, and are deleted only after the server returns 2xx. The rejected alternative, native handing batches to JS to upload through the app's API client, would stop all uploads whenever the app is killed and contradicts the "no recorded fix is lost" criterion.

## Consequences

- Uploads use one documented JSON batch contract (a `uuid` per fix for server-side dedupe), not the app's existing API client.
- App-specific logic that must run per event cannot live in JS while the app is killed; it belongs server-side.
- Implemented on both platforms with SQLite stores and platform HTTP clients. Upload retries and the stop timeout depend on in-process timers, so they are best-effort while a process is suspended (iOS) or the CPU sleeps (Android); an Android alarm is the backstop.
- 401 and 403 responses are not yet treated specially: they are retried like any failure (see `known-gaps.md`).

