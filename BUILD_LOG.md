# Build And Release Verification

## Version 1.0 - 2026-09-12

- Canonical build host: `192.168.178.93`, repository
  `/home/lenne/karoo-magicshine-controls`. The previous server app sources were
  backed up locally and compared with the local source before syncing.
- Passed `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug`.
- 18 unit tests passed, zero failed/skipped. Lint has no errors; existing
  non-blocking warnings about dependencies, strings, and logging remain.
- Version name `1.0`, version code `31`, package
  `com.lenne0815.karoomagicshine`. No sniffer version changes.
- APK SHA-256:
  `7c919acf2902667c37755e443d07b5a31f9d8398edba0b76eac1bd6a30f2faf7`.
- Signing certificate SHA-256 matches the downloaded Beta 1.4.1 release:
  `c9f19d25b940f3daefd0f1250157173fe66605df3a4dedeb658ae37e45efe536`.
  This preserves the existing server debug-signing identity and build path;
  "1.0" is the release label, not a change to Android signing/build type.

### Hardware checks

Tested on the USB-connected Karoo with an EVO 1700 next to it. Times below are
device-local time on 2026-09-12.

- Read-only connection queries returned B4 HIGH and B1 temperature. Repeated
  A4/A1 exchanges continued roughly every 30 seconds without A2 mode/OFF writes.
- Selecting 25 in the app, invoking the ride toggle action, and invoking it again
  produced 25 -> OFF -> 25. The shared state matched the transmitted commands.
- A real FLASH cancellation race was found during testing. GATT writes now
  finish before cancellation takes effect, with a timeout to bound failures.
- A transport timeout is reported as an operation failure, not cancellation of
  the telemetry job. A regression test verifies that polling continues after it.
- Before the requested duration change, cold-connect FLASH sent its first command at 17:11:48.684 and
  restored steady LOW/25 at 17:11:53.822, with acknowledgements and no disconnect.
  Its timer now starts after the first completed write, not during BLE startup.
- The user then shortened the ride-field FLASH to two seconds. The final APK
  sent FLASH at 17:15:03.360 and restored LOW/25 at 17:15:05.500 (two-second timer
  plus GATT write latency), with acknowledgements and no disconnect.
- A brief Bluetooth interruption was recognized at 17:12:08.017; the stored
  connection status changed to disconnected and telemetry cleared. Bluetooth
  was enabled again immediately after the interruption.
- The app's CONNECT button reconnected at 17:12:57.328 without an app restart;
  read-only telemetry resumed immediately and again at 17:13:28.
- Ride actions were invoked through the same production service used by the
  ride field. No ride was recorded and no user ride profile was modified.
- Native UI screenshots are captured from the actual Karoo. Playwright is used
  to inspect/capture those images in the browser, not to pretend the native
  Android UI is a web application.

Local evidence: `.artifacts/1.0-test-results/`,
`.artifacts/1.0-device-validation.log`,
`.artifacts/1.0-device-validation-final.log`, and `output/playwright/`.

## Historical marker

Build triggered at 2026-02-20 19:22:42 UTC.
