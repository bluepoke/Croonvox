# Spike: Audio Engine (ticket #2)

Throwaway spike that tests the Audio Engine design from [ADR 4](../adr/0004-native-oboe-audio-engine.md) and [ADR 5](../adr/0005-transpose-signalsmith-realtime.md) on real hardware, before the production engine is built. The code lives in the `:spikes:audio-engine` module (application ID `app.croonvox.spike.audio`) and is **not** used by the production app.

## What the spike does

- **One full-duplex Oboe stream pair** (stereo float output and mono float input), requested as *LowLatency* and *Exclusive*. The input uses the `VoicePerformance` preset and runs at the output's sample rate.
- **Backing Audio** is either a generated test tone (A-major pad with a 120 bpm click, accented on beat 1) or a decoded file. Files are resampled to the device rate with a linear resampler, which is spike quality only.
- **Live Transpose**: Signalsmith Stretch (`presetCheaper`, split computation). Its pre-roll is compensated with `outputSeek`, so playback starts sample-aligned with the timeline.
- **Takes**: the mic is captured on the same callback as playback. A Take stores its **Start Position** and its **Transpose**, and Review always plays it at that Transpose.
- **Latency Calibration**: 8 click bursts (4 ms, about 4 kHz) are played and detected in the input. The median offset in frames is stored per output route kind and sample rate, and applied to Review and to the alignment WAV. Before calibration, the app uses Oboe's reported input + output latency.
- **Monitoring**: dry mic added to the output with adjustable gain. It's available only on wired or USB routes, and disabled with an explanation on Bluetooth and the speaker.
- **Route changes**: when an output device is added or removed, or a stream disconnects, the app closes and reopens both streams. The transport stops, the Take is discarded (it belongs to the old route and maybe the old sample rate), the last measurement is cleared, and Monitoring is forced off. A stream that reports no device id is treated as the route kind *Other* rather than guessed.
- **Alignment WAV**: a stereo 16-bit file with the Backing Audio on the left and the compensated Take on the right, saved to `Android/data/app.croonvox.spike.audio/files/Music/`.

## Build and install

The spike modules are no longer part of the Gradle build. To build this one again, add `include(":spikes:audio-engine")` back to `settings.gradle.kts`.

```sh
./gradlew :spikes:audio-engine:installDebug
# pull alignment WAVs
adb pull /sdcard/Android/data/app.croonvox.spike.audio/files/Music/ ./spike-wavs/
# engine log
adb logcat -s CroonvoxSpike
```

## Measurement protocol

Run every step for each **device × route** you care about. Routes: built-in speaker, wired headphones, USB headset/DAC, Bluetooth. Note the device model, Android version and app build in the findings.

### 1. Stream configuration

1. Launch the spike and grant microphone access.
2. Under **Route & stream**, record the API, sample rate, burst/buffer, and the exclusive and low-latency flags for output and input.

**Expected:** AAudio, low latency yes/yes, exclusive yes on most Pixel-class devices. Shared mode is acceptable but note it.

### 2. Round-trip latency

1. Load the **Test tone**. Nothing needs to play.
2. Make an acoustic or electrical path from output to mic:
   - *Speaker:* phone on a table in a quiet room.
   - *Wired/USB:* a loopback adapter, or hold an earbud against the mic.
   - *Bluetooth:* hold an earbud against the phone mic.
3. Tap **Calibrate** 3 times and note each measured round trip and the click count. All 8 clicks should be detected.
4. Note the **reported** round trip (output + input) next to the **measured** one.

**Pass:** wired/USB round trip ≤ 30 ms (ADR 4's target for usable Monitoring), and the 3 runs agree within ±2 ms.

### 3. Monitoring feel (wired/USB only)

1. Plug in headphones, enable **Monitoring**, and set the gain.
2. Speak and sing short consonants ("ta-ta-ta") and listen for echo or comb filtering.
3. Rate it: 1 = unusable echo, 3 = noticeable but OK, 5 = feels instant.
4. Check that Monitoring is disabled, with its explanation, when switching to Bluetooth or the speaker.

### 4. Alignment of Takes

1. Calibrate the route you're testing. Turn Monitoring off, and turn off any hardware monitoring on the interface (or turn the headphone volume down) to avoid a feedback howl.
2. Load the **Test tone** and **Record** about 20 s while holding an earpiece against the mic, so the Take records the clicks themselves (acoustic loopback).
   Don't clap along: people clap 20–50 ms ahead of a regular click, and their tempo wanders, which hides the system's real offset and drift.
3. Tap **Review** and listen: the recorded clicks should sit on the played clicks.
4. **Save alignment WAV**, pull it, and analyse it:
   ```sh
   adb pull /sdcard/Android/data/app.croonvox.spike.audio/files/Music/ ./spike-wavs/
   python3 spikes/audio-engine/tools/analyze_alignment.py spike-wavs/Music/alignment-wired-<timestamp>.wav
   ```
   The script pairs every click (left channel) with the nearest recorded click or clap (right channel) and prints the **mean offset** (positive = Take is late), any **steps** (sudden jumps such as a burst lost in an xrun), and the **drift over 20 s** (a linear fit of the offsets over time, between steps). Add `-v` to list every pair.
   *Manual alternative:* in Audacity, zoom in on a click and the clap next to it, select from one onset to the other, and read the selection length (set the selection toolbar to milliseconds). Do this for the first 3 and last 3 claps. The mean is the offset; last-3 mean minus first-3 mean is the drift.

**Pass (loopback):** the mean offset is within ±5 ms, drift over 20 s is under 2 ms, and there are no steps. If you have to clap instead, only the drift is meaningful, and only roughly.

### 5. Transpose

1. **Play** the Test tone or a real song, then move Transpose across −6…+6 during playback.
2. Listen for glitches when changing, artifacts on voices and drums, and any tempo change.
3. Record a Take at +3, set the control to −2, and **Review**. Review must play at +3.

### 6. Load: xruns and CPU

1. Wired headphones, a real song loaded, **Transpose +4**, **Monitoring on**.
2. **Record** for 3 minutes while using the phone lightly (scroll the screen, lock and unlock it).
3. Record the **XRuns out / in** delta, the **Input shortfalls** delta, and the peak **Callback CPU**. The xrun totals accumulate across stream reopens.

**Pass:** 0 output and input xruns, 0 input shortfalls, CPU peak < 70 %.

### 7. Route changes

1. During **Play**, unplug wired headphones, plug them back in, then connect and disconnect Bluetooth.
2. Expected: no crash, streams reopen (the **Route changes** counter increments), the transport stops, the Take is discarded, the stored calibration for the new route is applied, and Monitoring is off until re-enabled on an allowed route.

## Findings

Copy one block per device. Leave cells empty when a measurement wasn't taken.

### Device: Galaxy Tab S6 lite (2022) · Android 14 · build da87d83

| | Speaker | Wired | USB-Microphone with wired closed Headphones | Bluetooth |
|---|---|---|---|---|
| API / rate / burst / buffer | AAudio / 48000 Hz / 192 / 384 | AAudio / 48000 Hz / 192 / 384 | AAudio / 48000 Hz / 192 / 384 | |
| Exclusive out/in · LowLatency out/in | no / no · yes / yes | no / no · yes / yes | no / no · yes / yes | |
| Reported round trip (ms) | 84.1 | n/a | n/a | |
| Measured round trip, 3 runs (ms) | 82.5 | 83 | 60.2 | |
| Clicks detected (of 8) | 8 | 8 | 8 | |
| Monitoring feel (1–5, 5 = best) | n/a | 3 (closed headphones; 4 with open) | 5, but masked by the AK-60's hardware monitoring (see notes) | n/a |
| Take alignment offset, mean (ms) | | 0.0, then +4.0 after one step (loopback) | −3.0 (loopback; stdev 0.0) | |
| Drift over 20 s (ms) | | 0.0 between steps; **1 step of +4.0 ms** at 7.5 s | 0.0 (loopback, 21 s) | |
| Transpose: glitch on change? artifacts? | no | no | | |
| XRuns out / in in 3 min (Transpose + Monitoring) | 0 / 0 | 1 / 0 | 0 / 0 | |
| Input shortfalls in 3 min | | 0 | | |
| Callback CPU peak (%) | | 70 | | |
| Route change: OK? | yes | yes | yes | |

Notes:

- Streams are shared, not exclusive (no MMAP path), on every route. Burst and buffer (192 / 384 frames ≈ 12 ms) explain only a fraction of the ≈ 83 ms round trip; the rest is the platform audio pipeline.
- Measured round trip on the speaker (82.5 ms) matches Oboe's reported value (84.1 ms), and all 8 clicks were detected. Oboe could not report the latency on the wired route (n/a); the measured value is still available.
- Monitoring at ≈ 83 ms was rated 3 ("noticeable but OK") with closed headphones and 4 with open ones. The open headphones let the direct sound of the voice mask the delayed copy. This is better than expected for this latency.
- 1 output xrun on wired in 3 min (pass criterion: 0).
- USB column: Aokeo AK-60 USB microphone, closed headphones plugged into the **AK-60's headphone jack**. Output and input therefore both go through the USB device. Round trip 60.2 ms.
- The AK-60 has zero-latency hardware monitoring on its headphone jack. With the spike's Monitoring on, both are audible. The hardware path masks the 60 ms software copy, so the "5" rates the hardware monitoring, not the spike's. The AK-60's hardware monitoring cannot be switched off, so the spike's Monitoring is not measurable with this mic.
- Take alignment on USB, first measured by clapping: −38.4 ms mean, +22.1 ms drift over 20 s. An acoustic loopback re-test (earpiece held against the mic, no human timing) gave **−3.0 ms mean, 0.5 ms stdev, −0.4 ms drift**. So the system aligns Takes correctly and does not drift. The clapping result was human timing: negative mean asynchrony, plus tempo wander that happened to look linear. Clapping is therefore unsuitable for this measurement, and step 4 now uses loopback by default. (Values re-analysed after the script started ignoring onsets in the first 50 ms of the file.)
- Callback CPU peak on wired with Transpose + Monitoring: 70 %, exactly at the pass limit.
- Wired loopback: the Take is aligned at exactly 0.0 ms, then **jumps by +4.0 ms at 7.5 s** and stays there. 4.0 ms at 48 kHz is 192 frames, exactly one burst. There is no continuous drift. The most likely cause is an xrun: an output underrun delays all later output by one burst, or an input overrun drops one. The spike's timeline counts callbacks, so it can't see the slip, and every xrun permanently shifts the rest of the Take by one burst. **Production consequence (ADR 4):** align Takes using stream timestamps (frame position + time from `getTimestamp`) rather than callback counting, or at least detect xruns and correct the Take index by the lost frames.

## Conclusions

Based on **one device**, the Galaxy Tab S6 Lite (2022): shared streams, the weak end of the target range. No low-latency device was available. A follow-up issue tracks measuring one.

- **≤ 30 ms wired/USB round trip: not met** (83 ms wired, 60 ms USB). AAudio grants only shared streams, and the platform pipeline accounts for about 70 ms beyond the 12 ms of burst + buffer. The app can't fix this, so Monitoring is no longer gated on a latency target.
- **Monitoring is still usable at about 83 ms** (rated 3 of 5 with closed headphones). Hardware Monitoring on a USB mic is better than any software Monitoring on this device.
- **Click calibration works**: it matches the reported latency, and with it applied, loopback Takes align within −3 to 0 ms with no drift on a single device. Repeatability across 3 runs was not recorded.
- **An xrun shifts the rest of a Take by one burst** (+4.0 ms step on wired; most likely an xrun). Production must align Takes using timestamps or xrun correction, not callback counting.
- **Transpose + Monitoring peaked at 70 % CPU** with 1 xrun in 3 min, so there's too little headroom on this chip. Production needs a callback load ≤ 50 %, using a larger Session buffer or a lighter configuration if needed. Transpose itself had no audible glitches on change (ADR 5 holds).
- **Clapping can't measure alignment** (humans clap ahead of the beat). The protocol now uses acoustic loopback.
- **ADR changes:** [ADR 13](../adr/0013-monitoring-and-take-alignment-after-engine-spike.md) amends ADR 4. ADR 5 is unchanged.
- **Not measured:** Bluetooth route, Monitoring on a low-latency device, input and output on separate devices (two clocks), 3-run calibration repeatability.

## Known spike shortcuts (not to be copied into production)

- Linear resampling of Backing Audio; production needs a band-limited resampler.
- Whole songs are held in memory as float, twice (source and device rate).
- Control calls wait for the audio thread to acknowledge Idle, with a 200 ms timeout and no hard guarantee.
- One process-wide engine singleton; no persistence of Takes; calibration is stored per route *kind*, not per device.
- Route changes stop the transport and discard the Take instead of continuing seamlessly.
- Stats cross JNI as an index-based array mirrored by hand in Kotlin.
