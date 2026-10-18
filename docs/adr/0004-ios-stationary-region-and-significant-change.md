---
status: accepted
---
# iOS goes stationary with a monitored region plus significant-change, and verifies the region on relaunch

While Stationary, the iOS engine stops continuous updates, arms a circular region (radius at least 100 m) around the last fix, and keeps significant-location-change monitoring as a safety net. A region exit, or a significant-change fix farther than the radius from the centre, emits the motion signal. This is the only approach that both saves battery and can relaunch a terminated app for a location event. Because regions persist across launches, the engine never trusts a surviving region: on arming it asks iOS for the region state and takes a one-shot location check, and resumes immediately if the device is already outside, since an exit event only fires on an inside-to-outside transition.

## Considered Options

- Continuous low-accuracy updates while stationary: rejected for battery, and a suspended app gets no relaunch.
- Visit monitoring (`CLVisit`) alone: rejected because visits are reported minutes after the fact and say nothing about leaving a place promptly.
- `CLMonitor` and `CLBackgroundActivitySession` as the core (iOS 17+): rejected for now to keep the iOS 15 floor; they can be adopted behind availability checks.
- A smaller region than 100 m: rejected because iOS region monitoring is coarse and wake-ups for small regions are unreliable.

## Consequences

- Wake-up latency and accuracy are set by iOS and can lag by minutes; the design promises best effort, not timeliness.
- A force-quit app may not be relaunched, and this is documented as best effort rather than guaranteed.
- Real-device behavior of region and significant-change wake-ups is still unverified (see `known-gaps.md`); only the simulator route test and the registration path have been exercised.
