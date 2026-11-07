# Immortalis Monitor

A native Android GPU monitoring application for devices using
MediaTek's GED GPU monitoring interface.

## Current status

Version: 1.0
Status: Initial working build
Platform: Android
Language: Java
Build system: Gradle

## Features

- Live GPU utilization monitoring
- Blocking and idle readings
- Selected OPP candidate display
- Raw GPU thermal sensor reading
- GPU activity history graph
- One-second refresh interval

## Target device

Initially developed and tested on:

- Device: POCO X8 Pro Max
- GPU: Arm Immortalis-G925 MC11
- Android: 16
- Root: KernelSU

The current implementation uses device-specific kernel interfaces.
Compatibility with other devices is not guaranteed.

## Requirements

- Android Studio or compatible Android build environment
- JDK 17
- Android SDK
- Android Gradle Plugin 9.0.0
- Root access for reading protected kernel interfaces

## Build

Clone the repository:

    git clone https://github.com/ramji14/ImmortalisMonitor.git
    cd ImmortalisMonitor

Build the debug APK:

    gradle assembleDebug

APK output:

    app/build/outputs/apk/debug/app-debug.apk

## Important notes

Some displayed kernel values are raw or provisional.
The thermal sensor reading and selected OPP candidate
should not be interpreted as verified GPU temperature
or actual operating frequency.

This project is currently monitor-only.

## Roadmap

- [x] Initial native Android dashboard
- [x] Live GPU readings
- [x] Activity history graph
- [ ] Improved dashboard layout
- [ ] Floating gaming overlay
- [ ] Additional monitoring metrics

## Developer

ramji14
