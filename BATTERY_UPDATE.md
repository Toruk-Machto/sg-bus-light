# SG Bus 0.2.1

- Cancel active HTTP calls when the tool is paused, hidden or destroyed. Stop the refresh loop even when Compose is not producing frames.
- Resume interrupted searches, route requests and offline downloads when the tool returns to the foreground.
- Bound each HTTP call to 25 seconds and each directory traversal to three minutes. Keep cached catalogues available after a failed refresh.
- Back off repeated arrival/route failures up to five minutes. Authorization errors wait for a manual retry or a new key instead of repeating automatically.
- Clear the SDK's screen ViewModel stores when an Android Activity is destroyed.

The interface, saved stops, pinned services, offline browsing and arrival refresh controls are unchanged. Normal foreground arrival refresh remains 30 seconds.

## Validation

JDK 17, Android SDK 36, ARM64 Android 14 Light Phone III emulator. Transit unit tests and instrumented cancellation, whole-call timeout, foreground resume, offline directory, search, favorites and Activity teardown checks. Tests use synthetic responses and a fake key.

Device test command after installing both debug APKs

```sh
adb shell am instrument -w sg.lightbus.test/sg.lightbus.BatteryChecks
```

These tests verify software behavior. Physical battery savings require comparison on a Light Phone III.
