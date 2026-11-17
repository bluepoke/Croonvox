# 5. Transpose with Signalsmith Stretch, in real time; each Take is bound to its Transpose

- Status: Accepted
- Date: 2026-11-14

## Context
Users want to sing in a different key without changing the tempo. The options were Rubber Band (GPL/commercial, heavier), Signalsmith Stretch (MIT, good quality, light) and SoundTouch (LGPL, audible artifacts at larger shifts).

## Decision
- Use **Signalsmith Stretch**, running **in real time inside the Audio Engine** (ADR 4).
- **Export renders offline with the same algorithm and parameters**, so the result matches what was heard.
- Range: **±6 semitones**, in **1-semitone steps**. No cent fine-tuning and no key changes within a song in v1.
- **A Take records the Transpose it was sung at.** A Mix always uses its Take's Transpose. The Transpose control only affects the next Take.
- The optional guide vocal (Vocal Stem) is transposed together with the Instrumental.

## Note: built in #10 (2026-11-17)
- The Audio Engine uses Signalsmith's `presetCheaper` preset (validated in spike #2) and reads ahead by the stretcher's latency, so its output stays sample-aligned with the Session timeline and Takes need no extra alignment.
- At Transpose 0 the stretcher is **bypassed**: the Backing Audio plays untouched. Switching to or from 0 crossfades over one audio callback. Export (#14, #24) must bypass at 0 as well, so it matches what was heard.
- Transpose belongs to the Session: it starts at 0 (the Last Transpose comes with #23), and plain playback outside a Session is never transposed.

## Consequences
- MIT-licensed, so it doesn't affect the open license question (ADR 1).
- Real-time stretching adds a small, constant processing latency. The engine has to account for it on the Session timeline.
