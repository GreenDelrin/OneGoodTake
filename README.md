# SocialMasterApp

Android (API 29+) app for musicians: record a social-media video with proper multitrack audio
(backing track + external audio interface), mix (gain/EQ/reverb per track), and one-button
auto-master for social-media loudness (-14 LUFS). Kotlin + Jetpack Compose + CameraX.

## Build
```
JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot" ./gradlew assembleDebug
```

## Market notes
- Closest competitors: Audigo (hardware-locked wireless mic + app, $7.99-15.99/mo), BandLab (free, social-first), n-Track (DAW).
- Differentiator: works with ANY USB audio interface, one-button auto-master for social loudness, one-time price.
- Suggested pricing: free tier (watermark/720p) + Pro $4.99/mo or $29.99 one-time. Launch discount to seed reviews.

## Next steps
1. Wire CameraX preview + recording into RecordingScreen.
2. USB audio interface device selection + capture (AudioRecord on UsbDevice).
3. Mux recorded audio with video; load wav/mp3 backing tracks.
4. Real DSP: biquad EQ, FDN reverb, lookahead limiter, K-weighted LUFS.
5. Export + share intents.