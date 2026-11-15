# 10. Track identity and processing pipeline

- Status: Accepted
- Date: 2026-11-15

## Decision
- A Track is identified by **Source + source ID** (e.g. `youtube:<videoId>`, `local:<content hash>`). Re-adding the same item opens the existing Track.
- **Track Pipeline** states: `Added → Downloading → Downloaded → Separating(model) → Ready`, plus `Failed(step, reason)`. Lyrics are fetched in parallel and never block.
- A Session can start once the Track is **Downloaded** (on Original Audio). It switches to the Instrumental when Separation finishes.
- Each step is a **resumable WorkManager job**: it survives process death, retries network errors with backoff, and shows a progress notification.

## Note: local import (#5)
Importing a picked or shared file (Local File Source) is **not** a WorkManager job. The read grant for a picked or shared content URI does not survive the process, so a job retried after process death could not read the file. The import runs in one short in-process step instead: copy and hash, then the Track moves `Added → Downloaded`. Staging files of imports that never finished are deleted at the next start. An import that died after the Track row was written but before the file was stored leaves a Track in `Added`; importing the same file again completes it.

## Consequences
- Pipeline steps must be idempotent and keyed by Track ID (and Separation Model).
- The pipeline state is persisted in Room and is the single source of truth for the UI.
