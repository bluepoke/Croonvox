# 7. Many Takes per Track; non-destructive Mix of one Instrumental and one Take

- Status: Accepted
- Date: 2026-11-14

## Decision
- **A Track has many Takes.** Each is kept until the user deletes it.
- **A Mix = one Backing Audio (Instrumental or Original Audio) + one Take + Mix Settings.** No comping and no multi-Take layering or duets in v1.
- **Mix Settings**: backing level, vocal level, **Vocal Nudge** (±200 ms on top of Latency Calibration), guide-vocal level (Vocal Stem, default 0), and **Vocal Effects**.
- **Vocal Effects** are applied at mix time only (Monitoring stays dry, ADR 4). v1 has a small preset set: reverb (Dry/Room/Hall), gentle compression, and a high-pass filter.
- Mixes are **non-destructive**: Takes are never modified, Mix Settings are persisted, and audio is rendered only for preview or Export.
- A Mix references its Instrumental by Track and Separation Model, so it can switch to a re-separated Instrumental.

## Consequences
- Preview and Export share the same offline render path (Audio Engine DSP + Signalsmith with the Take's Transpose, ADR 5).
- Storage grows with the number of Takes. Takes should be stored compactly (see the storage ADR).
