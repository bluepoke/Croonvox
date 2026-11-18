# 3. On-device Separation with user-selectable Separation Models

- Status: Accepted, amended by [ADR 15](0015-separation-model-catalogue-after-spike.md) (ONNX Runtime on CPU, Spleeter for Fast, UVR MDX-Net Inst HQ 4 for Best, downloaded on demand) and [ADR 19](0019-three-separation-models-and-default-model.md) (UVR MDX-Net Karaoke for Medium, Default Separation Model)
- Date: 2026-11-14

## Context
Separation (ADR 2) is ML inference. Running it on a server costs money, needs hosting and a network connection, and means uploading copyrighted audio. On-device models span a wide speed/quality range, from lightweight 2-stem models up to Demucs v4.

## Decision
- Separation runs **on-device only**. There is no server component.
- Separation goes behind a `Separator` interface with **several bundled or downloadable Separation Models**.
- The **user picks the trade-off** (e.g. *Fast* ↔ *Best quality*). Candidate models: a light 2-stem model (Spleeter-class or small MDX-Net/UVR) for *Fast*, and Demucs v4 / a large MDX-Net model for *Best*.
- Separation runs as a resumable background job, and its output (Instrumental + Vocal Stem) is cached per Track **per model**.

## Consequences
- An inference runtime (ONNX Runtime Mobile or LiteRT) becomes a core dependency.
- Large models should be downloaded on demand rather than bundled, to keep the APK small.
- Each model's license has to be checked before it ships (Demucs is MIT; check the individual UVR/MDX model weights).
- Re-separating with a different model must not invalidate Takes recorded against the earlier Instrumental. Separation has to preserve timing alignment.
