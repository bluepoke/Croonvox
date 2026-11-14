# 8. Storage formats and Export (WAV, MP3 via LAME, M4A/AAC via MediaCodec)

- Status: Accepted
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

## Consequences
- The NDK build bundles LAME and a FLAC codec (libFLAC, BSD). Keep LGPL compliance for LAME (dynamic linking, offer the source).
- Offline Sessions without a kept video fall back to audio-only.
