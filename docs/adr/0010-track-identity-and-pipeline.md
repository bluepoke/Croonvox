# 10. Track identity and processing pipeline

- Status: Accepted
- Date: 2026-11-15

## Decision
- A Track is identified by **Source + source ID** (e.g. `youtube:<videoId>`, `local:<content hash>`). Re-adding the same item opens the existing Track.
- **Track Pipeline** states: `Added → Downloading → Downloaded → Separating(model) → Ready`, plus `Failed(step, reason)`. Lyrics are fetched in parallel and never block.
- A Session can start once the Track is **Downloaded** (on Original Audio). It switches to the Instrumental when Separation finishes.
- Each step is a **resumable WorkManager job**: it survives process death, retries network errors with backoff, and shows a progress notification.

## Consequences
- Pipeline steps must be idempotent and keyed by Track ID (and Separation Model).
- The pipeline state is persisted in Room and is the single source of truth for the UI.
