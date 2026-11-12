# SG Bus for Light Phone III

Find Singapore bus stops by name, road, or stop code. View live arrival estimates, save frequently used stops, and use Nearby to find stops around you.

## Setup and privacy

Enter your own LTA DataMall API Account Key in **Settings → Enter key**. SG Bus stores the key on the device using Android Keystore-backed encryption. Requests to LTA DataMall use HTTPS and include the key and, for live arrivals, the bus stop code. The app uses location on the device to sort nearby stops by distance. Saved stops and cached stop and route data stay on the device.

## Building

This repository contains the source used by the Light Tool Library build service. The service builds the `tool` module against the Light SDK version pinned in `gradle.properties`.

To build locally, use the matching [Light SDK](https://github.com/lightphone/light-sdk) source checkout and replace its `tool` directory with the `tool` directory from this repository. Follow the SDK's Android Studio setup and build `:tool:assembleDebug` with JDK 17.

[LTA DataMall dynamic datasets](https://datamall.lta.gov.sg/content/datamall/en/dynamic-data.html)
