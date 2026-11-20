# Spike: Separation benchmark (ticket #3)

Throwaway spike that decides which Separation Models ship ([ADR 3](../adr/0003-on-device-separation-selectable-models.md)). A benchmark screen runs Fast and Best candidates on the device through ONNX Runtime, separates a chosen song in chunks, and writes the Instrumental and Vocal Stem so they can be listened to. The code is in two modules, neither used by the production app:

- `:spikes:separation-core`: pure Kotlin (JVM). STFT, resampler, chunked Separation, the model adapters and the catalogue. Unit-tested on the JVM; opt-in tests run the real models there too.
- `:spikes:separation`: the Android benchmark screen (application ID `app.croonvox.spike.separation`).

## What the spike does

- **Runtime:** ONNX Runtime 1.31.1 (`onnxruntime-android`, MIT), CPU execution provider by default, with XNNPACK or NNAPI selectable. LiteRT wasn't tried: every candidate already exists as ONNX, and getting them into LiteRT would take an extra conversion per model.
- **Pipeline:** the song is decoded (MediaCodec) into a temp file at its own sample rate, resampled to 44.1 kHz (polyphase windowed sinc, zero lag), separated in overlapping segments, resampled back, and written as two 16-bit WAVs. Each stem has **exactly as many frames as the decoded input**. All intermediate audio goes through temp files, so memory is bounded by one segment, not by the song.
- **Chunking:** segments overlap by 25 % (adjustable) and are cross-faded with triangular weights, as Demucs' `apply_model` does. Each model's segment length is its own: Spleeter 11.9 s, MDX-Net 5.9 s, Demucs 7.8 s.
- **Stems:** each model estimates one source, and the other stem is the input minus it, so Instrumental + Vocal Stem = input (as resampled to 44.1 kHz). Spleeter and Demucs estimate the Vocal Stem; UVR Inst models estimate the Instrumental; UVR Karaoke estimates the lead vocal.
- **Measurements per model:** model load time, separation wall time, real-time factor (separation wall time ÷ song duration; total RTF also includes resampling, load and writing), mean and max time per segment, process RSS (baseline, and peak sampled every 20 ms), frames in vs. out per stem, the **alignment lag** of each stem, and the thermal status before and after. The lag is the cross-correlation peak against the Original Audio over 1 s mid-song (±1024 frames; 0 = aligned), measured on exactly what was written, at the original rate, so resampling back is covered.
- **What the checks prove:** the length is guaranteed by construction (the pipeline writes exactly the input's frame count, and the unit tests cover this), so the screen shows it only as a sanity check. Only the lag of the stem the model **estimates** is informative: the other stem is input − estimate and lines up almost by construction. Spleeter, Demucs and UVR Karaoke estimate the Vocal Stem; UVR Inst HQ estimates the Instrumental.
- **Listening:** Play buttons for the original and both stems. Files are in `Android/data/app.croonvox.spike.separation/files/Music/separation/`.
- **Export:** "Copy results as Markdown" puts the device line and one table row per run on the clipboard (and saves them as `results-<timestamp>.md` next to the stems), ready to paste into the findings below.

## Candidates

| Id | Model | Tier | Download | Weights license | GPL-3.0 distribution (ADR 11) |
|---|---|---|---|---|---|
| `spleeter-2stems` | Deezer Spleeter 2-stems, ONNX export by sherpa-onnx | Fast | 74.7 MB `.tar.bz2` (2 × 39.3 MB) | Repo MIT; README: "the **code** of Spleeter is MIT-licensed", silent on the pretrained weights (published in the same repo's releases) | **To confirm.** Probably fine, but no statement covers the weights explicitly |
| `spleeter-2stems-fp16` | Same, fp16 weights | Fast | 35.3 MB `.tar.bz2` (2 × 19.7 MB) | As above | To confirm |
| `uvr-mdx-karaoke` | UVR MDX-Net Karaoke (`UVR_MDXNET_KARA`) | Fast | 29.7 MB `.onnx` | None: `TRvlvr/model_repo` has no license file; the UVR GUI's MIT license doesn't cover the weights | **No.** All rights reserved by default |
| `uvr-mdx-inst-hq3` | UVR MDX-Net Inst HQ 3 | Best | 66.8 MB `.onnx` | None (as above) | **No** |
| `uvr-mdx-inst-hq4` | UVR MDX-Net Inst HQ 4 | Best | 59.1 MB `.onnx` | None (as above). A third-party mirror tags it MIT, which doesn't count (below) | **No, license requested** from the authors ([#2444](https://github.com/Anjok07/ultimatevocalremovergui/issues/2444)) |
| `htdemucs` | Demucs v4 hybrid transformer, 4 sources | Best | Convert yourself (below): 174.3 MB `.onnx` | facebookresearch/demucs: MIT ("Demucs is released under the MIT license"); weights served by Meta from `dl.fbaipublicfiles.com` without a separate license | **To confirm.** Likely covered by the repo's MIT license |
| `htdemucs-ft-vocals` | Demucs v4 fine-tuned, vocals model of the bag | Best | Convert yourself: 174.3 MB `.onnx` | As above | To confirm |

UVR keeps each MDX-Net model's STFT parameters outside the ONNX file, in `model_data.json`, keyed by the MD5 of the file's last 10 000 KiB. The values used are from [TRvlvr/application_data](https://github.com/TRvlvr/application_data) and hard-coded in the catalogue: Karaoke n_fft 6144, dim_f 2048, compensate 1.035; Inst HQ 3 n_fft 6144, dim_f 3072, compensate 1.022; Inst HQ 4 n_fft 5120, dim_f 2560, compensate 1.019. They aren't always `n_fft = 2 · dim_f` (the sherpa-onnx metadata assumes that, wrongly for some models), so don't take the n_fft from the tensor shape.

The UVR models are in the benchmark only for comparison. Without a license they can't ship or be offered for download. If a UVR model turns out to be the only one that is good enough, the next step is to ask its authors for a license, not to ship it.

**Inst HQ 4 license request (2026-11-17):** Inst HQ 4 is the best candidate on the device (see Findings), so its license was requested from the UVR authors (Anjok07, aufr33) in [ultimatevocalremovergui#2444](https://github.com/Anjok07/ultimatevocalremovergui/issues/2444). The request asks:
- which license covers the weights (MIT, Apache-2.0 or CC BY 4.0 would be compatible with GPL-3.0),
- whether the project may redistribute the unmodified file from its own release asset, verified by checksum, as an on-demand download (not bundled in the APK),
- whether commercial redistribution is OK, since GPL-3.0 can't forbid it,
- how they want to be credited.

Until they answer, Inst HQ 4 can't ship. The Hugging Face collection [Politrees/UVR_resources](https://huggingface.co/Politrees/UVR_resources) tags all its 382 files, Inst HQ 4 among them, as MIT ("Copyright (c) 2025 Politrees"). Its file is byte-identical to TRvlvr's (SHA-256 `3c4b5b9b05090fdf238f38ba5046813982d50e2a652e9cb3324ea79720c3c9c8`). It isn't a valid basis, for two reasons:
- Politrees re-uploads models made by many others (UVR, Meta's Demucs, RoFormer weights by several authors, some of them labelled CC-BY-NC-SA elsewhere), and a re-uploader can't relicense someone else's work.
- The tag covers the whole collection, so it says nothing about any one model.

### Converting Demucs v4 (on a computer)

Demucs does its STFT inside the network, which ONNX can't export. [sevagh/demucs.onnx](https://github.com/sevagh/demucs.onnx) (MIT) moves the STFT and iSTFT out, and the spike does them in Kotlin (`DemucsSeparator`, after `standalone_spec` / `standalone_ispec`).

Both models were converted on 2026-11-16 with [`spikes/separation-core/scripts/convert_demucs.py`](../../spikes/separation-core/scripts/convert_demucs.py). It wraps the repo's `convert-pth-to-onnx.py`, which doesn't work as is, for three reasons:
- `--ft-vocals` fails: there is no model named `htdemucs_ft_vocals`. And for a bag, the original script exports `models[0]`, which in `htdemucs_ft` is the drums model. The wrapper takes `get_model("htdemucs_ft").models[3]` (weight row `[0, 0, 0, 1]`, the vocals model).
- Newer PyTorch defaults to the dynamo exporter, so the wrapper forces the TorchScript one (`dynamo=False`).
- `nn.MultiheadAttention`'s fused fast path (`aten::_native_multi_head_attention`) has no ONNX export, so the wrapper disables it.

Setup with [uv](https://docs.astral.sh/uv/) and Python 3.12 (Python 3.14 has no wheels for some dependencies):

```sh
git clone https://github.com/sevagh/demucs.onnx    # no submodules needed: they're only for the C++ CLI
cd demucs.onnx
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python numpy onnx onnxruntime onnxscript einops julius pyyaml tqdm omegaconf openunmix dora-search
uv pip install --python .venv/bin/python --reinstall torch torchaudio --index-url https://download.pytorch.org/whl/cpu   # after openunmix, which pulls the CUDA torchaudio
uv pip install --python .venv/bin/python --no-deps -e ./demucs-for-onnx
.venv/bin/python <croonvox>/spikes/separation-core/scripts/convert_demucs.py ./onnx-models   # → htdemucs.onnx, htdemucs_ft_vocals.onnx
```

Demucs also imports `diffq`, which has no wheel and needs a C compiler. It's only used for quantized checkpoints, and neither model is one. Without a compiler, put a stub in the venv's `site-packages/diffq/__init__.py` that defines `DiffQuantizer`, `UniformQuantizer` and `restore_quantized_state` as functions that raise.

The wrapper prints the tensor shapes and compares ONNX Runtime with PyTorch on the same input. Both exports matched to within about 1e-4, and both files are 174.3 MB (fp32). The export names only its first input and output, so the adapter tells the tensors apart by rank: waveform `[1, 2, 343980]` and spectrogram `[1, 4, 2048, 336]` in; frequency branch `[1, 4, 4, 2048, 336]` and time branch `[1, 4, 2, 343980]` out.

**Desktop check:** `RealModelsTest` (models dir with `htdemucs/`, `htdemucs-ft-vocals/` and a `test.wav`) passes for both. To check the Kotlin framing, the Vocal Stems were compared with demucs' own PyTorch `apply_model` on the same clip (no shifts, 25 % overlap). They agree to about 30 dB SDR; a Vocal Stem that is just the whole mix would score −5.6 dB. So `DemucsSeparator` matches `standalone_spec` / `standalone_ispec`. Desktop figures are in the reference section below.

**On the device**, push the files (or use **Import htdemucs.onnx** on the screen):

```sh
D=/sdcard/Android/data/app.croonvox.spike.separation/files/models
adb shell mkdir -p $D/htdemucs $D/htdemucs-ft-vocals
adb push onnx-models/htdemucs.onnx $D/htdemucs/
adb push onnx-models/htdemucs_ft_vocals.onnx $D/htdemucs-ft-vocals/
```

On the 3.7 GB tablet, lmkd killed the app before the first segment finished (see Findings).

## Build and install

The spike modules are no longer part of the Gradle build. To build this one again, or to run the `:spikes:separation-core` tests, add `include(":spikes:separation-core")` and `include(":spikes:separation")` back to `settings.gradle.kts`.

```sh
./gradlew :spikes:separation:installRelease   # non-debuggable: the Kotlin DSP runs at full speed
adb pull /sdcard/Android/data/app.croonvox.spike.separation/files/Music/separation/ ./separation-out/
```

Models download in the app (Spleeter from sherpa-onnx's GitHub release, UVR from TRvlvr's). To push them instead, put them in `/sdcard/Android/data/app.croonvox.spike.separation/files/models/<id>/` with the file names from the catalogue.

Always measure with the **release** build. A debuggable build runs the Kotlin STFT and resampler under the debugger-friendly runtime, which inflates wall time.

## Measurement protocol

Note the device (the screen shows model, SoC, Android version, RAM and cores) and the build. Close other apps. Let the device cool down between long runs: the screen records the thermal status before and after each run.

1. **Songs.** Use two songs: a typical 3–4 min song with a clear lead vocal and backing vocals, and a **long one (≥ 8 min)**. The long one checks that memory stays bounded. Prefer a 48 kHz file (e.g. Opus/WebM, as from YouTube) for one of them, so resampling is exercised.
2. **Install** every downloadable candidate, and the converted Demucs models if available.
3. **Default run:** CPU, 4 threads, 25 % overlap, arena on. Select all and **Run selected models**, once per song.
4. **Check per run:** frames in = Instrumental = Vocal Stem, and both lags 0. Both are shown in red when not met.
5. **Listen** to each stem with closed headphones against the original. Rate the Instrumental for karaoke use (1 = vocal clearly audible, 3 = faint vocal ghosts or artifacts, 5 = clean), and note artifacts (warbling, missing cymbals, pumping, backing vocals kept or removed). Also check the segment seams: every 5.9–11.9 s, depending on the model.
6. **Variations** (on the 3–4 min song, best Fast and best Best candidate only): threads 2 / 4 / 8, XNNPACK, NNAPI, arena off. These show the speed and memory headroom.
7. **Memory:** RSS a model leaves behind (ART heap, ORT arena) raises the next model's baseline, so for memory figures run **one model per app launch** (`adb shell am force-stop app.croonvox.spike.separation` between models). For the long song, note the peak RSS and whether any model failed with an `OutOfMemoryError` or the app was killed. If the app was killed, add the `adb logcat | grep -i lowmemory` output.
8. **Copy results as Markdown** and paste the rows below.

**Pass criteria (provisional, for the Fast tier on the weakest target device):** RTF ≤ 1 (a song separates in no more time than it takes to play), peak RSS increase ≤ 1 GB, all lengths equal and lags 0. The Best tier may be slower (it runs as a resumable background job, ADR 3), but should stay below RTF 5 with the same memory bound.

## Reference: JVM on a desktop (not a device)

The opt-in `RealModelsTest` on a 13th Gen Intel Core i7-13850HX, 4 threads, ORT CPU EP, the 10.9 s `audio_example.wav` from sherpa-onnx resampled to 48 kHz. These numbers only show that the pipeline works end to end and how the candidates rank relative to each other. Device numbers are typically several times slower. RSS includes the JVM heap (`-Xmx2g`), so it overstates what the models need.

| Candidate | Load (ms) | Separation (ms) | RTF | Segments (max ms) | Peak RSS (MB, +Δ) | Lag instr. / vocal |
|---|---|---|---|---|---|---|
| spleeter-2stems | 489 | 1634 | 0.15 | 2 (811) | 759 (+632) | 0 / 0 |
| spleeter-2stems-fp16 | 186 | 1397 | 0.13 | 2 (753) | 862 (+402) | 0 / 0 |
| uvr-mdx-karaoke | 39 | 4481 | 0.41 | 3 (1899) | 1564 (+994) | 0 / 0 |
| uvr-mdx-inst-hq3 | 70 | 10596 | 0.97 | 3 (3706) | 2878 (+2307) | 0 / 0 |
| uvr-mdx-inst-hq4 | 71 | 8229 | 0.75 | 3 (2905) | 2440 (+1865) | 0 / 0 |
| htdemucs | 937 | 7581 | 0.69 | 2 (4354) | 5241 (+5107) | 0 / 0 |
| htdemucs-ft-vocals | 575 | 5471 | 0.50 | 2 (2746) | 5288 (+4504)¹ | 0 / 0 |

¹ Ran second in the same JVM, so its +Δ is too low.

With ORT's CPU arena off, Inst HQ 3 peaked at +1186 MB instead of +2307 MB, at the same speed (Spleeter: +440 MB), and htdemucs at +2623 MB instead of +5107 MB (RTF 0.60). The arena keeps its peak allocations for reuse. All runs produced stems exactly as long as the input, with lag 0. Demucs is about as fast as the UVR Inst models, but needs about twice their memory, arena on or off.

## Findings

Copy one block per device. Leave cells empty when a measurement wasn't taken.

### **Device:** samsung SM-P613 · QTI SM7125 · Android 14 (API 34) · 3.7 GB RAM · 8 cores
**Song:** Adele -  Rolling in the Deep.mp3 (234 s, 44100 Hz, decoded in 17637 ms)

| Candidate | Tier | EP / threads / arena | Song | Load (ms) | Separation (s) | RTF | RTF total | Segment mean / max (ms) | Peak RSS (MB, +Δ) | Frames in / instr. / vocal | Lag instr. / vocal | Thermal | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Spleeter 2-stems (fp32) | Fast | Cpu / 4 / on | Adele -  Rolling in the Deep.mp3 | 659 | 36.8 | 0.16 | 0.17 | 1296 / 1422 | 627 (+487) | 10314752 / 10314752 / 10314752 | 0 / 0 | 0→0 | |
| Spleeter 2-stems (fp16 weights) | Fast | Cpu / 4 / on | Adele -  Rolling in the Deep.mp3 | 440 | 39.1 | 0.17 | 0.18 | 1405 / 1449 | 665 (+49) | 10314752 / 10314752 / 10314752 | 0 / 0 | 0→0 | |
| UVR MDX-Net Karaoke | Fast | Cpu / 4 / on | Adele -  Rolling in the Deep.mp3 | 173 | 314.4 | 1.34 | 1.35 | 5906 / 6313 | 1132 (+611) | 10314752 / 10314752 / 10314752 | 0 / 0 | 0→0 | |
| UVR MDX-Net Inst HQ 3 | Best | Cpu / 4 / on | Adele -  Rolling in the Deep.mp3 | 264 | 875.0 | 3.74 | 3.75 | 16479 / 18476 | 1909 (+834) | 10314752 / 10314752 / 10314752 | 0 / 0 | 0→0 | |
| UVR MDX-Net Inst HQ 4 | Best | Cpu / 4 / on | Adele -  Rolling in the Deep.mp3 | 687 | 779.0 | 3.33 | 3.34 | 14665 / 17296 | 1903 (+652) | 10314752 / 10314752 / 10314752 | 0 / 0 | 0→0 | |
| Spleeter 2-stems (fp32) | Fast | Xnnpack / 8 / off | Adele -  Rolling in the Deep.mp3 | 768 | 56.3 | 0.24 | 0.25 | 2035 / 2409 | 787 (+633) | 10314752 / 10314752 / 10314752 | 0 / 0 | 0→0 | |
| Spleeter 2-stems (fp32) | Fast | Nnapi / 8 / off | Adele -  Rolling in the Deep.mp3 | 680 | 39.1 | 0.17 | 0.18 | 1405 / 1762 | 477 (+328) | 10314752 / 10314752 / 10314752 | 0 / 0 | 1→1 | |
| UVR MDX-Net Inst HQ 4 | Best | Cpu / 4 / on | Adele -  Rolling in the Deep.mp3 | 421 | 781.5 | 3.34 | 3.35 | 14709 / 18413 | 1882 (+1737) | 10314752 / 10314752 / 10314752 | 0 / 0 | 0→1 | fresh launch (memory figure valid) |
| UVR MDX-Net Inst HQ 4 | Best | Cpu / 4 / off | Adele -  Rolling in the Deep.mp3 | 401 | 785.3 | 3.36 | 3.37 | 14783 / 16197 | 1191 (+1046) | 10314752 / 10314752 / 10314752 | 0 / 0 | 0→1 | fresh launch (memory figure valid) |

| Candidate | Instrumental for karaoke (1–5) | Artifacts / notes from listening |
|---|---|---|
| Spleeter 2-stems (fp32) | 2 | artifacts, dull, missing hihats, backing vocals kept |
| Spleeter 2-stems (fp16) | 2 | artifacts, dull, missing hihats, backing vocals kept |
| UVR MDX-Net Karaoke | 3 | some artifacts, cleaner, backing vocals kept |
| UVR MDX-Net Inst HQ 3 | 4 | very clean, but backing vocals removed |
| UVR MDX-Net Inst HQ 4 | 4 | very clean, but backing vocals removed |
| Demucs v4 htdemucs | n/a | n/a |
| Demucs v4 htdemucs_ft (vocals) | n/a | n/a |

**Song:** Guns N Roses - November Rain.mp3 (557 s, 44100 Hz, decoded in 42121 ms)

| Candidate | Tier | EP / threads / arena | Song | Load (ms) | Separation (s) | RTF | RTF total | Segment mean / max (ms) | Peak RSS (MB, +Δ) | Frames in / instr. / vocal | Lag instr. / vocal | Thermal | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Spleeter 2-stems (fp32) | Fast | Cpu / 4 / on | Guns N Roses - November Rain.mp3 | 539 | 83.9 | 0.15 | 0.16 | 1276 / 1435 | 669 (+521) | 24556608 / 24556608 / 24556608 | 0 / 0 | 0→0 | |
| Spleeter 2-stems (fp16 weights) | Fast | Cpu / 4 / on | Guns N Roses - November Rain.mp3 | 444 | 91.5 | 0.16 | 0.17 | 1404 / 1489 | 561 (+27) | 24556608 / 24556608 / 24556608 | 0 / 0 | 0→0 | |
| UVR MDX-Net Karaoke | Fast | Cpu / 4 / on | Guns N Roses - November Rain.mp3 | 235 | 744.4 | 1.34 | 1.35 | 5884 / 6517 | 1130 (+593) | 24556608 / 24556608 / 24556608 | 0 / 0 | 0→0 | |
| UVR MDX-Net Inst HQ 4 | Best | Cpu / 4 / on | Guns N Roses - November Rain.mp3 | 670 | 1897.5 | 3.41 | 3.42 | 15024 / 22844 | 1903 (+601) | 24556608 / 24556608 / 24556608 | 0 / 302 | 0→1 | |

| Candidate | Instrumental for karaoke (1–5) | Artifacts / notes from listening |
|---|---|---|
| Spleeter 2-stems (fp32) | 1 | artifacts, faded instruments when voice removed |
| Spleeter 2-stems (fp16) | 1 | artifacts, faded instruments when voice removed |
| UVR MDX-Net Karaoke | 1 | voice can be heard clearly |
| UVR MDX-Net Inst HQ 3 | n/a | n/a |
| UVR MDX-Net Inst HQ 4 | 4 | voice can be heard sometimes |
| Demucs v4 htdemucs | n/a | n/a |
| Demucs v4 htdemucs_ft (vocals) | n/a | n/a |

Notes:

- personal impression: user should choose between Spleeter 2-stems (fp32) (fastest), UVR MDX-Net Karaoke (intermediate), and UVR MDX-Net Inst HQ 4 (best) with approx. runtime given
- Axl Rose's voice seems to be hard to separate
- **Memory figures:** only the rows marked "fresh launch" were measured one model per app launch (protocol step 7). In the other rows, the models ran one after another in the same launch, so each run started from the RSS the previous one left behind. Their +Δ is too low. For example, Spleeter fp16's +49 MB started from a baseline of about 616 MB, and Inst HQ 4's earlier +652 MB from about 1251 MB. Their absolute peaks are still valid.
- **Inst HQ 4, arena on vs. off (fresh launches):** arena off lowers the peak from 1882 MB (+1737) to 1191 MB (+1046), at the same speed (RTF 3.34 vs. 3.36). With the arena off, it is just at the provisional 1 GB bound, and about 900 MB below the RSS at which lmkd killed htdemucs.
- **htdemucs** was killed twice by Android's low memory killer (lmkd), as the foreground app, about 35 s after starting: at 2.29 GB and 2.09 GB RSS ("device is low on swap and thrashing"). No measurements.

## Conclusions

Measured on one device, a Samsung Galaxy Tab S6 Lite (SM-P613: Snapdragon 720G / SM7125, 3.7 GB RAM, Android 14). It's the weakest target device so far. Songs: "Rolling in the Deep" (234 s) and "November Rain" (557 s), both 44.1 kHz MP3.

- **Fast: Spleeter 2-stems (fp32).**
  - RTF 0.15–0.16, so a 4-minute song takes about 40 s. Peak RSS 627 MB (+487 MB, the first run of its launch).
  - It passes the speed and memory criteria by a wide margin.
  - Quality is weak, rated 2 and 1: artifacts, a dull sound and missing hi-hats (Spleeter puts them in the Vocal Stem). On "November Rain", instruments fade wherever the voice is removed. Backing vocals stay in the Instrumental.
  - The fp16 weights sound the same, are about 6 % slower and are half the download (35 vs. 75 MB). fp32 was chosen because it is faster.
- **Best: UVR MDX-Net Inst HQ 4**, if its weights get licensed (see Licenses).
  - RTF 3.34–3.41, so a 4-minute song takes about 13 min and a 9-minute song about 32 min.
  - Peak RSS with the arena off is 1191 MB (+1046 MB, fresh launch): just at the provisional 1 GB bound, and about 900 MB below the RSS at which lmkd killed htdemucs.
  - Rated 4 on both songs: very clean, though the voice is sometimes audible on "November Rain".
  - It **removes backing vocals** along with the lead vocal.
- **Not chosen:**
  - **Inst HQ 3:** slower than HQ 4 (RTF 3.74), with the same rating.
  - **UVR MDX-Net Karaoke:** RTF 1.34 fails the Fast bound. Rated 3 and 1, it is clearly behind HQ 4. It keeps backing vocals.
  - **Demucs v4 (both models):** converted and verified on the desktop, but killed by lmkd on the tablet, in the foreground, at 2.09–2.29 GB RSS. It would need less memory first (fp16 weights, shorter segments), which this spike didn't try.
  - **BS-RoFormer / Mel-Band RoFormer** (estimated from their configs, not measured): about 10–34× the compute of htdemucs, more than 1 GB of attention scores per layer when ONNX Runtime computes them in full, and unclear weight licenses. Not viable on this class of device.
- **Runtime and execution provider:** ONNX Runtime on the CPU execution provider, 4 threads, **CPU arena and memory-pattern optimisation off**.
  - Turning the arena off saves about 690 MB on Inst HQ 4 at the same speed.
  - XNNPACK was slower (RTF 0.24 vs. 0.16 on Spleeter). NNAPI was no faster than the CPU (0.17), and Google deprecated it in Android 15.
  - Both of those runs used 8 threads with the arena off, so they aren't clean one-factor comparisons. Thread counts 2 / 4 / 8 weren't compared on the CPU.
- **Pipeline:** every run wrote stems exactly as long as the input, and the stem the model estimates always had lag 0.
  - The one non-zero lag (302 frames, Inst HQ 4's Vocal Stem on "November Rain") is a measurement artifact. That stem is input − Instrumental, and mid-song it holds only residue, so the cross-correlation peak has nothing to lock onto.
  - Peak RSS is the same on the 234 s and 557 s songs, so memory stays bounded by one segment.
- **Thermal:** long Inst HQ 4 runs go from status 0 to 1, and their slowest segment takes 18–23 s against a mean of about 15 s. The tablet throttles slightly but doesn't fail.
- **Licenses:**
  - **Spleeter weights:** still to confirm.
  - **Inst HQ 4:** requested from the UVR authors on 2026-11-17 ([#2444](https://github.com/Anjok07/ultimatevocalremovergui/issues/2444)), no answer yet. The MIT tag on Politrees/UVR_resources isn't a valid basis (see Candidates).
  - **Demucs:** no longer needed for this catalogue.

## Proposed amendment to ADR 3 (draft)

Adopted as [ADR 15](../adr/0015-separation-model-catalogue-after-spike.md), which is authoritative. Kept here as the spike's original proposal. Points in _italics_ were open when it was written.

- **Runtime:** ONNX Runtime (`onnxruntime-android`, MIT) on the CPU execution provider, 4 threads, with the CPU arena and memory-pattern optimisation **off**. No XNNPACK or NNAPI: neither was faster on the weak device. The full ORT build adds about 34 MB per ABI (arm64, uncompressed) to the APK. A reduced build containing only the operators the shipped models need (ORT's `--include_ops_by_config`) is a follow-up.
- **Catalogue (first release):** both models are downloaded on demand, not bundled.
  - *Fast:* **Spleeter 2-stems, fp32** (75 MB), once its weight license is confirmed.
    - RTF ≈ 0.16 on the weak device.
    - It separates up to 11 kHz only, so the Instrumental keeps the band above.
    - Backing vocals stay in the Instrumental.
    - The quality is that of a quick first version, not a clean karaoke track.
  - *Best:* **UVR MDX-Net Inst HQ 4** (59 MB), **only once its authors license the weights** ([#2444](https://github.com/Anjok07/ultimatevocalremovergui/issues/2444)).
    - RTF ≈ 3.4 on the weak device.
    - It removes backing vocals along with the lead vocal.
    - Its STFT parameters (n_fft 5120, dim_f 2560, compensate 1.019) aren't in the ONNX file, so they ship with the catalogue entry.
    - _If the license is refused or doesn't arrive in time: ship Fast only, or look for another Best candidate (Demucs with less memory, or another architecture)._
  - The model picker shows each model's **expected duration** for the Track (Track length × the model's RTF), and says whether the model **keeps or removes backing vocals**. The two models differ in that, not just in speed and quality.
  - Not offered: Demucs v4 (killed by lmkd on a 3.7 GB device), UVR MDX-Net Karaoke (too slow for Fast, worse than Inst HQ 4 for Best), Inst HQ 3 (slower than HQ 4 at the same quality), RoFormer models (too heavy, licenses unclear).
- **Glossary:** "Instrumental" is defined as Original Audio "with the lead vocal removed". With Inst HQ 4, backing vocals are removed too. _Decide whether the term stays as is, with a per-model note, or becomes "with the lead vocal (and, depending on the Separation Model, backing vocals) removed"._
- **Distribution:** models are downloaded from a release asset the project controls, with a checksum, rather than from third-party URLs, so the files can't change under the app. This needs the redistribution permission asked for in #2444.
- **Pipeline:** Separation runs at 44.1 kHz. Original Audio at another rate is resampled with a band-limited, zero-lag resampler, and the stems are resampled back to the Original Audio's rate. Stems have exactly the Original Audio's length. Separation runs in overlapping segments, cross-faded, with memory bounded by one segment. The model estimates one source, and the other stem is the input minus it (at 44.1 kHz). So Instrumental + Vocal Stem = the Original Audio as resampled, band-limited to about 21 kHz.
- **Background job:** Separation runs as a resumable job (ADR 3), checkpointed per segment so a killed process resumes where it stopped.
  - On the weak device, Fast takes about 0.16× the Track's length and Best about 3.4×.
  - Best peaks at about 1.2 GB RSS on a 3.7 GB device. In the background, Android kills a process sooner than in the foreground, so the job runs as a **foreground service** with a notification. _Its foreground service type (e.g. `dataSync`, or `mediaProcessing` from Android 15) and any time limits are to be checked._

## Known spike shortcuts (not to be copied into production)

- Model files have no checksum verification, and downloads don't resume.
- The decoded song and the 44.1 kHz intermediates are raw float temp files (8 bytes per frame): about 300 MB of cache for a 10-minute song.
- Stems are 16-bit WAV for listening and are clipped at ±1. Input − estimate can exceed that on loud masters, so an audible click may be clipping, not the model. Production stores FLAC (ADR 8).
- The STFT runs in Kotlin on the calling thread, with a mixed-radix FFT written for the spike.
- A run only lasts while the app is in the foreground (the screen stays on). Production needs a resumable background job.
- No Demucs `shifts` (random time-shift averaging), no track-level standardisation before Demucs (`demucs.separate` divides by the track's std; the model also normalises each segment), and no UVR denoise (running the model on −x as well). Both improve quality at 2× the cost or more.
- Peak memory is process RSS sampled every 20 ms, so a very short spike between samples can be missed.
