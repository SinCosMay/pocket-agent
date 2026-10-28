# Screen-off and autonomous schedule validation

Validated on 28 October 2026 using the USB-connected Xiaomi 22041216I, Android 14
(API 34). All phone mutations used the separate
`dev.pocket.agent.historyvalidation` installation; the user's installed Pocket
Agent, accessibility settings and saved jobs were not modified.

- Runtime suite: **180/180 passed** (`npm test --prefix runtime`).
- Android debug app and instrumentation APKs: **built successfully**.
- Android debug lint: **passed**.
- Device instrumentation: **11/11 passed** (`ScreenOffScheduleDeviceTest` and
  `AutonomyDeviceTest`).
- An active scripted inference/tool turn completed after sending the physical
  display to sleep. The display stayed off through completion.
- A saved one-time job fired with the physical display asleep and no open Activity
  or pre-existing runtime owner. It created the runtime, executed its offline
  scripted phone-capabilities probe, recorded success and disabled its one-time
  trigger. Start was within 20 seconds of its five-second test deadline.
- The CPU wake-lock lifecycle test verified acquisition, repeated active events,
  idle release and reacquisition.

The schedule test grants exact-alarm app-op access only to the validation package.
It does not enable accessibility or use live provider credentials. The validation
app is force-stopped after the tests to remove any residual background test work.

These checks verify screen-off operation and alarm-driven runtime startup. They do
not establish reliability under prolonged forced Doze, reboot, manufacturer process
termination, loss of network, or live provider authentication failures. Exact-alarm
access and enabled automation remain prerequisites for precise unattended delivery;
without access, the app reports inexact timing and uses the fallback path.
