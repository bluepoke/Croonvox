# 12. Platform baseline: Android 10+ (minSdk 29), arm64-v8a

- Status: Accepted (resolves the open device question)
- Date: 2026-11-15

## Decision
- **minSdk 29** (Android 10). targetSdk is the current stable release.
- Native ABIs: **arm64-v8a** for release, plus **x86_64** for the emulator. No 32-bit.

## Rationale
This covers about 95% of active devices. AAudio is mature, scoped storage and MediaStore behave predictably, and 64-bit only keeps the native libraries (Oboe, Signalsmith, LAME, FLAC, the ML runtime) manageable.

## Consequences
- Latency and Separation performance still vary widely across devices. Benchmark on real low-end hardware early.
