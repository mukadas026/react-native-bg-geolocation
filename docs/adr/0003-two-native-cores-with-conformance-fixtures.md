---
status: accepted
---
# Two native cores (Kotlin, Swift) verified by shared conformance fixtures

The tracking state machine, buffer rules and sync queue are implemented separately in Kotlin and Swift. A platform-neutral JSON scenario suite (sequences of fixes, motion events and timeouts mapped to expected state transitions and recorded fixes) runs against both implementations and doubles as the written spec. Rejected: a shared C++ core (JNI and ObjC++ bridging, awkward for Swift-first iOS APIs) and Kotlin Multiplatform (toolchain and binary-size burden on every consuming app).

## Consequences

- Behavior changes must update the fixtures first; a divergence between platforms fails a test instead of shipping silently.
- Intentional platform differences (iOS stationary region, AOSP motion sensor) are expressed as engine inputs in the fixtures, not as separate rules.
- The suite lives in `conformance/`: a written spec, JSON fixtures, and a TypeScript reference model used to prove the fixtures consistent. All four implementations (reference model, Kotlin core, Swift core) run the same fixtures via `yarn test:cores`.
- The cores are pure and take the clock as input. Stores, uploaders, engines and runtimes are outside the suite and currently untested (see `known-gaps.md`).

