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

1. Calibrate the route you're testing.
2. Load the **Test tone** and **Record** about 20 s, clapping or saying "ta" exactly on each click.
3. Tap **Review** and listen: claps should land on the clicks.
4. **Save alignment WAV**, pull it, and open it in an editor (Audacity etc.). Measure the offset between the click onsets (left channel) and the clap onsets (right channel) at several points.

**Pass:** the mean offset is within ±10 ms, which is human clapping error. There's no drift between the start and end of the Take.

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

### Device: _model_ · Android _version_ · build _git sha_

| | Speaker | Wired | USB | Bluetooth |
|---|---|---|---|---|
| API / rate / burst / buffer | | | | |
| Exclusive out/in · LowLatency out/in | | | | |
| Reported round trip (ms) | | | | |
| Measured round trip, 3 runs (ms) | | | | |
| Clicks detected (of 8) | | | | |
| Monitoring feel (1–5) | n/a | | | n/a |
| Take alignment offset, mean (ms) | | | | |
| Drift over 20 s (ms) | | | | |
| Transpose: glitch on change? artifacts? | | | | |
| XRuns out / in in 3 min (Transpose + Monitoring) | | | | |
| Input shortfalls in 3 min | | | | |
| Callback CPU peak (%) | | | | |
| Route change: OK? | | | | |

Notes:

-

## Conclusions

_Fill in after measuring at least two devices._

- Is the ≤ 30 ms wired/USB round trip met? If not, what's the buffer/burst floor?
- Is the click calibration repeatable enough to be the production approach, or do we need a loopback-only mode?
- Can Signalsmith `presetCheaper` + Monitoring run without xruns on the weakest target device? Should production use `presetDefault`?
- Changes needed to ADR 4 / ADR 5 (if any):

## Known spike shortcuts (not to be copied into production)

- Linear resampling of Backing Audio; production needs a band-limited resampler.
- Whole songs are held in memory as float, twice (source and device rate).
- Control calls wait for the audio thread to acknowledge Idle, with a 200 ms timeout and no hard guarantee.
- One process-wide engine singleton; no persistence of Takes; calibration is stored per route *kind*, not per device.
- Route changes stop the transport and discard the Take instead of continuing seamlessly.
- Stats cross JNI as an index-based array mirrored by hand in Kotlin.
