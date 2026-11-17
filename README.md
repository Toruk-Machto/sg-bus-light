# SG Bus for Light Phone III

Find Singapore bus stops by name, road, or stop code. See the next three arrival estimates, their destinations, and crowding where LTA provides it. Tap a service number for its route, first and last buses, and service intervals. Save stops or pin a specific service at a stop. Use **Find direct bus** to check which services reach another stop in the right direction.

## Setup and privacy

Enter your own LTA DataMall API Account Key in **Settings → Enter key**. SG Bus checks the key with LTA before saving it on the device using Android Keystore-backed encryption. Requests to LTA DataMall use HTTPS and include the key and, for live arrivals, the bus stop code. The app uses location on the device to sort nearby stops by distance.

In **Settings → Offline browsing**, download the directory over a connection. Stop search, route details, schedules, and direct-bus lookup work from the downloaded data. Live arrivals require a connection. Saved stops, pins, and downloaded data stay on the device.

## Building

This repository contains the SG Bus tool module and the SDK patch used for the signed release. The SDK version is pinned in `gradle.properties`.

To build locally, use the matching [Light SDK](https://github.com/lightphone/light-sdk) source checkout and replace its `tool` directory with the `tool` directory from this repository. Follow the SDK's Android Studio setup and build `:tool:assembleDebug` with JDK 17. Use `./gradlew -DlightSdk.abiFilters=arm64-v8a :tool:assembleRelease` for a smaller ARM64 build for the Light Phone III.

Bus arrival, stop, route, and service data comes from [LTA DataMall](https://datamall.lta.gov.sg/content/datamall/en/dynamic-data.html) under the [Singapore Open Data Licence](https://datamall.lta.gov.sg/content/datamall/en/SingaporeOpenDataLicence.html).

## Battery update build requirement

Before building against SDK 0.2.4, apply `sdk-patches/0001-lifecycle-and-device-tests.patch` from this repository in the SDK checkout with `git apply /path/to/0001-lifecycle-and-device-tests.patch`. The patch releases screen resources when Android destroys an Activity and permits instrumentation-only test APIs. It keeps the production tool restrictions in place. Automated build services must apply this patch too to reproduce the lifecycle fix. The signed APK in Releases already includes it.

See [BATTERY_UPDATE.md](BATTERY_UPDATE.md) for the changes, emulator checks and battery measurement limits.
