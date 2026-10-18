# Conformance suite

Platform-neutral scenarios that every native core (Kotlin, Swift) must satisfy (ADR-0003). A scenario feeds a sequence of inputs, with a virtual clock, to a core and asserts the observable result after each step. These rules are the written spec for the tracking state machine, buffer and sync queue.

`reference/model.ts` is an executable version of these rules. It exists to prove the fixtures are self-consistent and to resolve ambiguity; it is not shipped. Run it with `yarn test:conformance`.

## Fixture format

```json
{
  "name": "unique-kebab-name",
  "description": "one line",
  "config": { "stopTimeout": 1 },
  "server": [500, 200],
  "steps": [
    { "at": 0, "input": { "type": "start" }, "expect": { "state": "moving" } }
  ]
}
```

- `config`: overrides on the conformance defaults below. Fixtures never rely on product defaults.
- `server`: HTTP status per upload request, consumed in order. `0` means network error. Once exhausted, every request returns `200`.
- `steps[].at`: virtual time in seconds, non-decreasing. The core receives the clock; it never reads wall time.
- `steps[].expect`: every key is optional and asserted only when present.

### Conformance defaults

| Key | Value | Unit |
|---|---|---|
| `distanceFilter` | 10 | meters |
| `stopTimeout` | 5 | minutes |
| `stationaryRadius` | 25 | meters |
| `autoSync` | true | |
| `maxBatchSize` | 100 | fixes |
| `syncRetryBaseDelay` | 10 | seconds |
| `syncRetryMaxDelay` | 300 | seconds |
| `maxRecordsToPersist` | -1 (unlimited) | fixes |

### Inputs

| `type` | Fields | Meaning |
|---|---|---|
| `start` / `stop` | | Enable / disable tracking. |
| `changePace` | `isMoving` | Manual override. |
| `fix` | `id`, `dx`, `dy` | Engine delivers a location. `dx`/`dy` are meters east/north of the origin (0°N, 0°E); the harness converts to lat/lon with 1° = 111194.93 m. Cores compute distance with haversine, R = 6371008.8 m. |
| `motionSignal` | | Engine reports movement while stationary (Android: significant motion; iOS: stationary-region exit). |
| `tick` | | Time passes; no other input. |
| `connectivity` | `online` | Network reachability changed. Initially online. |
| `restart` | | Process killed and relaunched. Persisted: enabled/tracking state, buffer. Lost: anchor, last recorded fix, retry backoff. |

### Expectations

| Key | Asserts |
|---|---|
| `state` | `stopped`, `moving` or `stationary` after the step. |
| `engine` | `off`, `continuous` (location running) or `armed` (location off, waiting for a movement trigger) after the step. |
| `events` | Exact ordered list of events emitted during this step. |
| `requests` | Exact ordered list of upload requests issued during this step, each a list of fix ids, oldest first. |
| `buffer` | Exact ordered list of fix ids in the buffer after the step. |

Events: `{type:"enabledchange", enabled}`, `{type:"motionchange", isMoving}`, `{type:"location", id}`, `{type:"buffer-overflow", dropped:[ids]}`.

## Rules

**Tracking state**
1. `start` while `stopped`: state `moving`, engine `continuous`, anchor and last recorded fix cleared; emits `enabledchange true`. `start` in any other state is ignored.
2. `stop` while not `stopped`: state `stopped`, engine `off`; emits `enabledchange false`. The buffer is kept. Fixes in `stopped` are ignored.
3. The **anchor** is the position the device has been settled around, with the time it was set (`anchorSince`). On a fix while `moving`: if there is no anchor, or the fix is farther than `stationaryRadius` from it, the anchor becomes the fix and `anchorSince` the current time. Exactly `stationaryRadius` away is still inside.
4. **Stop detection** is evaluated after every input while `moving`: if an anchor exists and `at - anchorSince >= stopTimeout * 60`, the state becomes `stationary`, engine `armed`, anchor cleared, and `motionchange false` is emitted. It does not depend on fixes arriving, since iOS delivers none while the device is still. No fix is recorded by the transition. Without a first fix there is no anchor, so the timeout does not run.
5. `motionSignal` while `stationary`: state `moving`, engine `continuous`, anchor cleared, the next fix is recorded regardless of `distanceFilter`; emits `motionchange true`. In any other state it is ignored.
6. `changePace(true)` behaves as rule 5. `changePace(false)` while `moving` behaves as rule 4's transition (no timeout needed). Both are ignored in other states, including `stopped`.
7. Fixes while `stationary` are ignored for recording and for state.

**Recording**
8. A fix while `moving` is recorded when there is no last recorded fix, when rule 5 flagged it as the first fix after resuming, or when it is at least `distanceFilter` from the last recorded fix. Recording appends the id to the buffer and emits `location`.
9. If `maxRecordsToPersist >= 0` and the buffer then exceeds it, the oldest fixes are dropped and one `buffer-overflow` event lists them (after the `location` event).

**Sync**
10. Rules 10 to 16 apply to automatic uploads, which happen only when `autoSync` is true. Every recorded fix triggers an attempt, unless offline or a retry is pending (rule 14).
11. An attempt takes up to `maxBatchSize` oldest buffered fixes as one request. A `2xx` status deletes them from the buffer and the attempt continues with the next batch in the same step until the buffer is empty.
12. Any other status (including `0`) keeps the batch, ends the attempt, increments the failure count and schedules a retry at `at + min(syncRetryBaseDelay * 2^(failures-1), syncRetryMaxDelay)`. A `2xx` resets the failure count.
13. An input at or after the retry time triggers an attempt.
14. While a retry is pending and its time has not been reached, no attempt is made.
15. `connectivity online:true` clears any pending retry and failure count and triggers an attempt. Offline makes no attempt.
16. `restart` clears the retry and failure count, and triggers an attempt if the buffer is not empty and the device is online. The tracking state is restored: `moving` restarts stop detection from the next fix, `stationary` stays armed.

## Not yet specified

Geofences, heartbeat, odometer, accuracy filtering, HTTP 401/403 pausing, speed-based stop detection (AOSP engine). Each needs fixtures before its native implementation starts.
