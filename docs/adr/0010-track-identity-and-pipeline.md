# 10. Track identity and processing pipeline

- Status: Accepted (amended by ADR 14, ADR 17, ADR 20)
- Date: 2026-11-15

## Decision
- A Track is identified by **Source + source ID** (e.g. `youtube:<videoId>`, `local:<content hash>`). Re-adding the same item opens the existing Track.
- **Track Pipeline** states: `Added → Downloading → Downloaded → Separating(model) → Ready`, plus `Failed(step, reason)`. Lyrics are fetched in parallel and never block.
- A Session can start once the Track is **Downloaded** (on Original Audio). It switches to the Instrumental when Separation finishes, if that is the Track's Backing Audio choice (ADR 20).
- Each step is a **resumable WorkManager job**: it survives process death, retries network errors with backoff, and shows a progress notification.

## Note: Vocal-Free Tracks (ADR 14)
A Vocal-Free Track skips Separation and goes `Downloaded → Ready`. `Ready` means every step the Track needs is done, not that stems exist.

## Note: deleted stems (ADR 17)
Deleting a Ready Track's stems on the storage screen moves it to **Stems Deleted**, which the Track Pipeline doesn't queue. Opening the Track (its screen, which is also its Session since ADR 20) moves it back to `Separating` with the same model.

## Note: local import (#5)
Importing a picked or shared file (Local File Source) is **not** a WorkManager job. The read grant for a picked or shared content URI does not survive the process, so a job retried after process death could not read the file. The import runs in one short in-process step instead: copy and hash, then the Track moves `Added → Downloaded`. Staging files of imports that never finished are deleted at the next start. An import that died after the Track row was written but before the file was stored leaves a Track in `Added`; importing the same file again completes it.

## Consequences
- Pipeline steps must be idempotent and keyed by Track ID (and Separation Model).
- The pipeline state is persisted in Room and is the single source of truth for the UI.
