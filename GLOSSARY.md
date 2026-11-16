# Croonvox — Domain Glossary

Ubiquitous language for the project. Decisions are recorded in `docs/adr/`.

| Term | Meaning |
|---|---|
| **Source** | A pluggable provider of songs: *search → candidates → resolve to stream/file*. One Source per service: each service behind the NewPipe adapter (YouTube, SoundCloud, …) is its own Source. Its name is the namespace of Track identities (`youtube:<id>`). See ADR 1, ADR 10. |
| **Local File Source** | Files the user picks or shares into the app. Not searched: the file is *imported* (copied as Original Audio), and its identity is `local:<SHA-256 of the content>`, so the same content always maps to the same Track. |
| **Track** | A song the user added, identified by Source + source ID. Owns Original Audio, stems per Separation Model, optional video, metadata, Lyrics, Takes and Mixes. See ADR 10. |
| **Track Pipeline** | Resumable processing states: Added → Downloading → Downloaded → Separating(model) → Ready / Failed. A Vocal-Free Track skips Separating. |
| **Ready** | The Track Pipeline state in which every step the Track needs is done: stems exist, or the Track is Vocal-Free. See ADR 14. |
| **Original Audio** | A Track's full audio as obtained, including the original vocals: downloaded from a Source (e.g. a music video's audio stream) or an imported local file (audio or video), kept as is. |
| **Separation** | ML source separation of Original Audio into an Instrumental and a Vocal Stem. See ADR 2. |
| **Separation Model** | A selectable on-device ML model used for Separation; the user trades speed vs. quality. See ADR 3. |
| **Instrumental** | Original Audio with the lead vocal removed; the default playback layer in a Session. |
| **Vocal Stem** | The isolated original lead vocal produced by Separation (could serve as an optional guide vocal). |
| **Vocal-Free** | A Track whose Original Audio has no lead vocal (e.g. a karaoke video's audio), so it skips Separation and has no Instrumental or Vocal Stem. Set by the user. See ADR 14. _Avoid_: karaoke track, instrumental track. |
| **Backing Audio** | Whatever plays under the singer in a Session: Instrumental by default, or Original Audio. Always the Original Audio on a Vocal-Free Track. |
| **Take** | One microphone recording of the user singing over a Track, at a fixed Transpose. A Track has many Takes. Its audio and timing are never modified after recording; only its name can change. |
| **Session** | Singing over a Track: Backing Audio plays (video follows the Audio Engine clock), Lyrics display, optional Monitoring, Practice Loop or recording of a Take, then Review. See ADR 9. |
| **Start Position** | Where on the Track timeline a Take begins; Takes may cover only part of the song. |
| **Practice Loop** | A–B section looped without recording, for learning a passage. |
| **Guide Vocal** | The Vocal Stem blended into the headphones (Session) or into a Mix at low level. Never recorded. |
| **Review** | Post-recording playback of a Take with Keep / Discard / Record again. |
| **Monitoring** | Optional, dry, real-time software routing of the mic to **wired/USB** headphones during a Session. Never over Bluetooth. Warns when the measured round trip is high. See ADR 4, ADR 13. |
| **Hardware Monitoring** | Zero-latency monitoring done by the audio interface itself (e.g. a USB mic's headphone jack), outside the app. Preferred over Monitoring when available. |
| **Audio Engine** | Native (C++/Oboe) component that owns the Session timeline: playback, Transpose, capture, Monitoring. |
| **Latency Calibration** | Per-device/route measurement of the input/output offset, applied to Takes so they align. |
| **Transpose** | Pitch-shift of Backing Audio (and guide Vocal Stem) by −6…+6 semitones, tempo unchanged. A property of each Take. See ADR 5. |
| **Last Transpose** | The Transpose a Track's control was last set to in a Session, whether or not a Take was kept. A new Session for the Track starts at it. It never changes existing Takes. _Avoid_: default transpose, saved key. |
| **Lyrics** | Text of the song, from a LyricsProvider (LRCLIB first) or user edits; *plain* or *line-synced*. See ADR 6. |
| **Lyrics Offset** | Per-Track time shift between lyric timestamps (studio timing) and the Track's audio. |
| **Tap-to-Sync** | Editor in which the user taps per line during playback to turn plain Lyrics into synced Lyrics. |
| **Page View** | Lyrics display as static pages/scrolling text; works with plain Lyrics. |
| **Karaoke View** | Time-synced display: the current line is highlighted and word progress is interpolated within the line. Requires synced Lyrics. |
| **Mix** | Non-destructive combination of one Backing Audio + one Take + Mix Settings. Rendered only for preview/Export. See ADR 7. |
| **Mix Settings** | Backing level, vocal level, Vocal Nudge, guide-vocal level, Vocal Effects. |
| **Vocal Nudge** | Manual ±200 ms alignment of a Take, applied on top of Latency Calibration. |
| **Vocal Effects** | Mix-time presets for the Take (reverb, compression, high-pass). Never applied to Monitoring. |
| **Export** | Rendering a Mix, or a Track's Backing Audio alone (Backing Export), to WAV, MP3 or M4A/AAC in `Music/Croonvox/` or via share sheet. See ADR 8. |
| **Backing Export** | An Export of a Track's Backing Audio (Instrumental or Original Audio) alone at a chosen Transpose, with no Take, e.g. as a backing track for a live performance. _Avoid_: karaoke track, instrumental export. |

## Open questions
- None. License → ADR 11, platform baseline → ADR 12.
