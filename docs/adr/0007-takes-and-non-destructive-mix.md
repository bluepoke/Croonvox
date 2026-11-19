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

## Note: built in #14 (2026-11-19)
- **Keeping a Take creates a default Mix** over the Backing Audio the Take was sung over, at the current **Review Balance** (backing −20 dB unless changed, vocal 0 dB, no Vocal Nudge), so it sounds like Review. More Mixes come from a Take's "New Mix"; a Track can have any number. Deleting a Take deletes its Mixes; exported files stay.
- **A Mix spans what Review plays**: from the Take's Start Position until its last sample has sounded, not the whole song.
- **Alignment**: Take sample *i* sounds at Start Position + *i* − (Latency Calibration offset − Vocal Nudge). Until Latency Calibration (#9), the offset is the engine's estimate from recording, as in Review.
- **The preview plays in real time** through the Session's Audio Engine, like Review, so it starts at once and level and Nudge changes are heard live. It plays the Mix's own Backing Audio and returns to the Session's afterwards. The **Offline Render** (Export) shares the DSP code with it (`BackingRenderer`: Signalsmith Stretch with the same preset, read-ahead and priming, and the same Take mixing), which makes preview and Export sound the same. That replaces "preview uses the Offline Render" above: rendering first would take seconds to tens of seconds before anything plays, and again after every change.
- The Offline Render streams the Backing Audio through and keeps only a short window of it, so a Session that holds the same song in memory isn't doubled.
- A Mix keeps the Instrumental of its Separation Model while those stems are stored. Once they are gone after a re-separation, it switches to the Track's current Instrumental. While a Track has no Instrumental (e.g. its stems are being made again), a Mix on the Instrumental can't be previewed or exported.
- **Export to WAV runs on the Track's screen**, with progress and Cancel; leaving the Mix Editor or the screen cancels it and removes the unfinished file. It is not yet a WorkManager job (spec #1); that can follow when MP3/M4A (#15) make Exports longer.
- The Guide Vocal level and Vocal Effects come with #16.
