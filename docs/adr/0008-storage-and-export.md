# 8. Storage formats and Export (WAV, MP3 via LAME, M4A/AAC via MediaCodec)

- Status: Accepted, amended by [ADR 18](0018-video-streaming-only-backlog.md) (no offline video; video is streaming only)
- Date: 2026-11-14

## Decision
**Internal storage (app-private, per Track):**
- **Original Audio** is kept as downloaded (Opus/AAC), with no re-encode.
- **Instrumental / Vocal Stem** are stored as **stereo FLAC**, per Separation Model.
- **Takes** are stored as **mono FLAC** at the Audio Engine's native rate (typically 48 kHz).
- **Video is streamed by default.** An optional per-Track "keep video offline" setting downloads a copy capped at ≤720p.
- Metadata (Tracks, Takes, Mixes, Lyrics, Mix Settings) lives in **Room**.
- A **storage screen** shows per-Track usage and lets the user delete stems, video or Takes. Stems can be regenerated as long as the Original Audio exists.

**Export formats:**
- **WAV**: 16-bit PCM (24-bit optional).
- **MP3**: via **LAME** (LGPL) through the NDK. Default VBR V2, with 320 kbps CBR optional. Tagged with **ID3** (title, artist "feat. <singer>", cover art from the thumbnail).
- **M4A/AAC**: via the platform **MediaCodec** encoder, with MP4 metadata tags.
- Output goes to **MediaStore** `Music/Croonvox/`, and can also be shared through the Android share sheet.

## Note: Take sample rate (#8)
The Session timeline runs at the **loaded Backing Audio's sample rate**, not the device's native rate. The Audio Engine opens its streams at that rate and Oboe resamples to and from the device, so a band-limited resampler doesn't have to live in the app. Takes are therefore stored at the Backing Audio's rate, usually 44.1 or 48 kHz. Separation keeps stems sample-aligned with the Original Audio, at the same rate, so a Take fits both the Original Audio and the Instrumental. Takes are written as 24-bit FLAC through libFLAC.

## Consequences
- The NDK build bundles LAME and a FLAC codec (libFLAC, BSD). Keep LGPL compliance for LAME (dynamic linking, offer the source).
- Offline Sessions without a kept video fall back to audio-only.

## Note: Backing Export built in #24 (2026-11-19)
- The Track's screen (Track detail and the Session in one, ADR 20) has a **Backing track → Export** entry once the Original Audio is stored. It offers the Instrumental the Track uses (only once it is Ready with one) and the Original Audio. It starts at the Backing Audio the Track plays and at its Last Transpose. A Transpose changed there is for that export only.
- The Offline Render renders the Backing Audio whole, from its first to its last frame, with the same `BackingRenderer` as playback (bypassed at Transpose 0, ADR 5). The length is only known at the end, so the WAV header is written over once it is.
- **WAV only for now** (16-bit, or 24-bit), like the Mix Export. MP3, M4A/AAC and cover art come with #15 for both. A WAV carries the title and the artist in a RIFF INFO list (INAM, IART); MediaStore gets them too.
- Titles name the layer and the Transpose: "Title (Instrumental, +2)", "Title (Original, −1)", no Transpose at 0. A Vocal-Free Track's Original Audio is called "Instrumental" (ADR 14).
