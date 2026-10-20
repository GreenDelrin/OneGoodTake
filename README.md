# SocialMasterApp

Android (API 29+) app for musicians: record a social-media video with proper multitrack audio
(backing track + external audio interface), mix (gain/EQ/reverb per track), and one-button
auto-master for social-media loudness (-14 LUFS). Kotlin + Jetpack Compose + CameraX.

## Build
```
JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot" ./gradlew assembleDebug
```

## Direct USB capture (experimental)
- Direct USB / UAC2 is selected by default when a USB input is available; grant USB access and test the input.
- Capture runs through native `usbdevfs` isochronous USB requests at 48 kHz, then records the chosen first input or first two inputs as the app's existing 16-bit mono/stereo WAV.
- Native capture claims only the input stream interface so Android can continue routing backing playback through the device's output interface. If native capture fails, the app automatically tries Android `AudioRecord`; both Android profiles remain selectable.
- The app restores the selected settings, backing file, current take, and mix/master workspace after relaunch; it does not resume playback or recording.
- Adapted from the supplied `UsbAudioProbe` project, whose README declares the code MIT-licensed; that folder contained no separate license or copyright notice. Confirm attribution before commercial distribution. This path has not yet been tested with physical hardware.

## Editing
- Recording uses a full-screen camera preview with social safe-area guides and a backing-track restart control.
- Mix keeps the video visible, provides backing/take lanes, trim-in/trim-out, per-track effects, and a streaming audio preview that responds to edits while playing.
- Final video trims snap to the next encoded keyframe; frame-accurate re-encoding is not implemented.

## Market notes
- Closest competitors: Audigo (hardware-locked wireless mic + app, $7.99-15.99/mo), BandLab (free, social-first), n-Track (DAW).
- Differentiator: works with ANY USB audio interface, one-button auto-master for social loudness, one-time price.
- Suggested pricing: free tier (watermark/720p) + Pro $4.99/mo or $29.99 one-time. Launch discount to seed reviews.

## Next steps
1. Refine mastering-stage controls and overall visual consistency.
2. Improve export quality and trim precision.
