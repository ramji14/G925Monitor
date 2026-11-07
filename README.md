G925 Monitor

A native Android GPU monitoring app with a real-time dashboard and floating Gaming HUD.

G925 Monitor reads GPU telemetry from device-specific MediaTek kernel interfaces and displays live GPU performance information in an easy-to-read interface.

«Project status: Active development
Current version: 1.4
Platform: Android
Language: Java»

Features

Live GPU monitoring

- Real-time GPU utilization
- GPU loading, blocking and idle readings
- GPU frequency monitoring
- GPU temperature monitoring
- Live GPU activity history graph

Gaming HUD

- Floating, draggable GPU monitoring overlay
- Compact and expanded HUD layouts
- Live GPU load, frequency and temperature
- Loading, blocking and idle metrics
- Session average and peak GPU utilization
- GPU activity history graph
- Adjustable HUD transparency
- Configurable refresh interval
- Notification controls to hide, show or stop the HUD

Screenshots

Screenshots will be added here as the project develops.

Tested device

Component| Details
Device| POCO X8 Pro Max
GPU| Arm Immortalis-G925 MC11
Android| Android 16
Root solution| KernelSU

Compatibility note: The current implementation uses device-specific MediaTek kernel interfaces. It has been developed and tested on the device listed above. Compatibility with other devices and GPUs is not guaranteed.

Requirements

- Android 8.0 (API 26) or newer
- Root access for reading protected GPU kernel interfaces
- Overlay permission for the floating Gaming HUD
- Notification permission for visible foreground-service notifications

Installation

1. Visit the "GitHub Releases" (https://github.com/ramji14/G925Monitor/releases) page.
2. Download the latest APK.
3. Install the APK on your device.
4. Grant root access when requested.
5. Grant display-over-other-apps permission to use the floating HUD.
6. Start monitoring.

Building from source

Requirements

- JDK 17
- Android SDK
- Android SDK Platform 35
- Android Gradle Plugin 9.0.0
- Compatible Gradle installation

Clone the repository

git clone https://github.com/ramji14/G925Monitor.git
cd G925Monitor

Build the debug APK

gradle assembleDebug

The generated APK will be available at:

app/build/outputs/apk/debug/app-debug.apk

Technical notes

G925 Monitor reads GPU information from device-specific kernel interfaces, including:

- "/sys/kernel/ged/hal/gpu_utilization"
- "/proc/gpufreqv2/gpufreq_status"

These interfaces are not standard Android APIs and may differ between devices and kernel versions.

Root access is required to read the protected telemetry interfaces. The floating HUD uses Android's overlay and foreground-service functionality.

Reported values depend on the device kernel and its exposed interfaces. Missing or unsupported readings may be displayed as unavailable.

Roadmap

- [x] Native Android GPU monitoring dashboard
- [x] Live GPU utilization readings
- [x] GPU frequency and temperature monitoring
- [x] GPU activity history graph
- [x] Floating Gaming HUD
- [x] Compact and expanded HUD modes
- [x] Adjustable transparency and refresh interval
- [x] HUD notification controls
- [ ] Support for additional devices and GPUs
- [ ] Further performance and UI improvements

Contributing

Contributions, bug reports and feature suggestions are welcome!

If you want to improve device compatibility, add monitoring features or improve the interface, feel free to fork the repository and submit a pull request.

Please include device model, Android version and relevant kernel details when reporting hardware-specific issues.

License

G925 Monitor is licensed under the Apache License 2.0.

You are free to use, modify and distribute this project under the terms of the license.

See the "LICENSE" (LICENSE) file for the full license text.
