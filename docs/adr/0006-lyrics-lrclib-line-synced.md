# 6. Lyrics from LRCLIB behind a LyricsProvider; line-synced with interpolated word progress

- Status: Accepted
- Date: 2026-11-14

## Context
Lyrics have to be fetched from the web and shown in a Page View or a Karaoke View. LRCLIB is free and keyless, and offers plain plus line-synced (LRC) lyrics. Musixmatch has word timing but is paid and restrictive. Genius is plain text and needs scraping. Music video titles are noisy, and video timing often differs from the studio recording that LRC timestamps follow.

## Decision
- An **`LyricsProvider`** interface. **LRCLIB** is the first implementation, returning plain and/or line-synced lyrics.
- **Matching**: clean up the video title, query by artist/title/duration, and **the user confirms or picks** a result. Manual search is the fallback.
- **Page View** works with any lyrics. **Karaoke View** needs synced lyrics. Word progress within a line is **interpolated** across the line's time span.
- Each Track has a **Lyrics Offset** that can be adjusted during a Session.
- A **tap-to-sync editor** turns plain lyrics into line-synced lyrics.
- Fetched and edited lyrics are stored with the Track. User edits take precedence over the provider's version.

## Deferred
- **Automatic Lyrics Alignment**: on-device forced alignment against the Vocal Stem to get true word timings and the offset automatically.

## Consequences
- The app needs network access for lyrics even when the Source is a local file.
- The LRC parser has to tolerate the enhanced/word-level LRC extensions so they can be used if a provider supplies them.
