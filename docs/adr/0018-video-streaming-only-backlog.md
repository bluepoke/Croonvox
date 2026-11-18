# 18. Video in the Session is a streaming-only nice to have

- Status: Accepted (amends ADR 4 and ADR 8)
- Date: 2026-11-18

## Context
ADR 4 made the music video a follower of the Audio Engine clock, and ADR 8 added an optional per-Track offline copy capped at 720p. Neither is needed to sing, record or export. The offline copy also adds downloads, storage and a storage-screen category, and downloading streams conflicts with platform terms (ADR 1).

## Decision
- Video in the Session is **nice to have**. It is not part of v1 or v2 and is tracked in the Backlog (#33, replacing #19).
- If it is built, video is **streamed only**: ExoPlayer plays it muted and follows the Audio Engine clock, as in ADR 4. **No offline copy is downloaded or stored.**
- Without a video or a connection, the Session runs audio only.

## Consequences
- A Track owns no video. Storage (ADR 8) holds only Original Audio, stems and Takes, and the storage screen (#20) has no video category.
- There is no "keep video offline" setting.
- Lyrics are the only thing that follows the Audio Engine's playback position until video is built.
