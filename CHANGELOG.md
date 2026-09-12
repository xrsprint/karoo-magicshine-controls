# Changelog

## 1.0 - 2026-09-12

First release without the Beta label. Android version code 31 remains higher than
Beta 1.4.1's code 30, so the normal Karoo Extensions Library update flow applies.

### Fixed

- Connecting no longer changes the beam/mode or sends OFF as part of telemetry.
- Battery and temperature are queried with read-only A4/A1 requests every 30
  seconds while connected. Values expire after 90 seconds without a response,
  checked at each poll; repeated identical responses still count as fresh.
- Remote BLE disconnections stop polling and repeating commands and clear the
  connection/telemetry display. CONNECT checks the actual connection, not a
  cached label.
- App and ride-field controls share the same output state; the redundant
  persisted toggle flag is no longer read or written.
- Failed or stalled OFF writes no longer prevent bounded GATT cleanup.
- Shortens the ride-field FLASH to two seconds at the user's request.
- Stopping FLASH lets an in-flight GATT write finish before restoring the prior
  mode. Its timer starts after the first flash write, not during BLE
  connection startup.
- Service destruction cancels flash, connection retries, and controller jobs.
- Listener registration is safe across UI and Bluetooth callback threads.

### Verification

- Regression tests cover periodic polling, link loss, session replacement,
  cancellation, failed/stalled disconnects, shared output state, battery mapping,
  and lengths/checksums of generated protocol frames.
- APK signing identity is retained from previous releases for in-place updates.

Historical Beta release notes are retained in README.md. The separate sniffer
apps are unchanged by this production-app release.
