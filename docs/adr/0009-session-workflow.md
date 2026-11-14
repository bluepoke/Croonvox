# 9. Session workflow: start anywhere, countdown, practice loop, guide vocal, review

- Status: Accepted
- Date: 2026-11-15

## Decision
- **Start anywhere**: a Take stores its **Start Position** on the Track timeline. Partial Takes are valid. There is no punch-in into an existing Take.
- **Count-in**: an optional **time-based** countdown (visual, with an optional click). No beat detection in v1.
- **Practice Loop**: an A–B section loops *without recording*. Monitoring and Transpose stay available.
- **Guide Vocal**: the Vocal Stem can be blended into the headphone mix during a Session. It is never recorded.
- **Headphones** are strongly recommended for recording. Without them the app warns but does not block.
- **Input level meter** and clipping warning while recording, plus one **input gain** setting per device.
- **Review** after stopping: immediate playback of the Take against Backing Audio, with **Keep / Discard / Record again**. *Keep* creates the Take and a default Mix.

## Deferred
- Live pitch display, intonation curve, scoring.
- Beat-accurate count-in (needs tempo detection).
