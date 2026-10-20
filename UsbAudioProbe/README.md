# USB Audio Probe

A small, open reference implementation of a **USB Audio Class 2.0 capture driver for Android**,
similar in purpose to the proprietary eXtream driver: it bypasses Android's audio stack and reads
isochronous audio straight from the interface, giving you every input channel at 24-bit.

No libusb, no third-party code. Native side is ~300 lines of C++ using Linux `usbdevfs` ioctls on the
file descriptor Android's `UsbDeviceConnection` provides.

## Build
1. Open this folder in Android Studio (Koala or newer). Let it sync (it will create the Gradle wrapper
   and offer to install the NDK/CMake if missing).
2. Run on a physical phone (the emulator has no USB host).

## Use
1. Plug the interface into the phone with USB-C, grant the RECORD_AUDIO prompt (Android requires it
   before granting USB access to an audio device), tap **Connect & start capture**, accept the USB prompt.
2. The log shows every capture alt-setting found, the channel count chosen, and the clock status.
3. Play guitar: the per-input meters show which physical input carries what. This is the quickest way
   to see where your dry vs. effected signal is.
4. Choose "Record ALL" or a single input, tap Record. Files are 32-bit float WAV in
   `Android/data/com.example.usbaudioprobe/files/Music/` (pull with `adb pull`).

## Files
- `cpp/usb_audio_core.{h,cpp}`  descriptor parser, clock setup, isochronous URB capture, ring buffer
- `cpp/jni_bridge.cpp`          JNI glue
- `NativeUsb.kt`                Kotlin API: start(fd, descriptors, rate), read(), peaks(), stop()
- `MainActivity.kt`             demo UI (claims interfaces, meters, WAV recording)

To reuse in your app: copy the three cpp files + CMakeLists + NativeUsb.kt, and follow the
claim -> start -> read flow in `MainActivity.startCapture()`. The JNI function names include the
package `com.example.usbaudioprobe`; rename them if your package differs.

## Limitations (it is a test harness, not a finished driver)
- Written without access to a device: **not hardware-tested**. The descriptor parser is unit-tested
  on a synthetic descriptor; the USB streaming path is not. Expect to debug on first run.
- UAC2 only, integer PCM only (16/24/32-bit). UAC1 and vendor-specific modes are reported and refused.
  Scarlett Gen 1 units may not present UAC2; Gen 2/3/4 should.
- Capture only (no playback/monitoring). Picks the alt-setting with the most channels, fixed 48 kHz.
- No hot-plug re-attach, no sample-rate change at runtime, no float-format devices.
- If the system audio service grabs the device, `claimInterface(force=true)` detaches it; system audio
  routing to the interface stops while the app owns it.

## Licence
MIT. Do what you like, including commercially.
