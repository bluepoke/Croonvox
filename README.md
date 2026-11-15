# Croonvox

An Android karaoke and singing-practice app. Find a music video, remove the vocals, sing over the instrumental with the lyrics on screen, and export your performance.

> **Status:** early development. The design is settled (see [docs](#documentation)); the Gradle project is scaffolded, implementation has not started yet.

## Features (planned for v1)

- **Browse sources**: search music videos on YouTube, SoundCloud, Bandcamp, PeerTube and others through [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), or import local files.
- **Vocal removal**: on-device source separation into an instrumental and a vocal stem. You choose between a fast and a high-quality model.
- **Sing and record**: low-latency playback and recording on one sample-accurate timeline. Optional video follows the audio.
- **Real-time monitoring**: hear your own voice dry in wired or USB headphones. Not supported over Bluetooth, because its latency is too high.
- **Transpose**: shift the key by ±6 semitones without changing the tempo, in real time.
- **Lyrics**: fetched from [LRCLIB](https://lrclib.net), shown as pages or karaoke-style synced lines. Includes an offset adjustment and a tap-to-sync editor.
- **Takes and mixing**: record many takes per song and mix them non-destructively, with levels, alignment nudge, a guide vocal and vocal effects (reverb, compression, high-pass).
- **Export**: WAV, MP3 or M4A/AAC with tags, saved to `Music/Croonvox/` or shared.

## Architecture at a glance

| Layer | Technology |
|---|---|
| UI | Kotlin, Jetpack Compose |
| Audio Engine | C++ on [Oboe](https://github.com/google/oboe) (AAudio), full-duplex |
| Transpose | [Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) |
| Separation | On-device ML models (ONNX Runtime / LiteRT) |
| Persistence | Room; audio stored as FLAC |
| Background work | WorkManager (resumable track pipeline) |

Modules:

- `core`: domain core in pure Kotlin (model, use cases, and the ports `Source`, `Separator`, `LyricsProvider`, `AudioEngine`); its test fixtures hold in-memory fakes for every port
- `data`: Room database and app-private file store, implementing the core's interfaces
- `app`: Android application (UI, wiring), in two flavors: `network` (with network Sources) and `local` (Local File Source only, Play-Store-safe; see ADR 1)
- `engine`: native Audio Engine (C++ via CMake and the NDK)
- `spikes/*`: throwaway spikes that validate ADRs on hardware (e.g. [`audio-engine`](docs/spikes/audio-engine-spike.md)); not shipped

## Documentation

- [`GLOSSARY.md`](GLOSSARY.md): domain glossary (Track, Take, Mix, Session, Separation, …)
- [`docs/adr/`](docs/adr/README.md): architecture decision records

## Building

Requirements:

- Android Studio (or a JDK 17+ and the Android SDK)
- Android SDK platform 37

AGP downloads the NDK and CMake automatically on first build. CMake fetches Signalsmith Stretch from GitHub, so the first build needs network access.

```sh
./gradlew assembleNetworkDebug assembleLocalDebug   # both flavors
./gradlew installNetworkDebug                       # install on a connected device
./gradlew test                                      # unit tests (domain core on the JVM)
```

CI (GitHub Actions) runs the same build and tests on every push and pull request.

Without Android Studio's environment, point Gradle at a JDK first, for example its bundled runtime:

```sh
export JAVA_HOME=/path/to/android-studio/jbr
```

Target platform: Android 10+ (minSdk 29), `arm64-v8a` (plus `x86_64` for the emulator).

## Distribution

Croonvox is distributed outside the Google Play Store (GitHub releases / F-Droid), because extracting audio from streaming platforms conflicts with Play policies. You are responsible for complying with the terms of the platforms you use and with copyright law.

## License

[GPL-3.0-or-later](LICENSE). Required because NewPipeExtractor is GPL-3.0; see [ADR 11](docs/adr/0011-license-gpl-3.md).
