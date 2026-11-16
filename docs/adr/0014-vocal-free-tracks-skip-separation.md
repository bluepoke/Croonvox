# 14. Vocal-Free Tracks skip Separation

- Status: Accepted (amends ADR 2, ADR 7, ADR 10)
- Date: 2026-11-16

## Context
Some Original Audio has no lead vocal to remove, for example the audio of a karaoke video. Running Separation on it costs minutes of CPU and battery, and the model can damage the result (strip backing vocals, add artifacts). ADR 2 and ADR 10 made Separation a step every Track goes through, so there was no way to skip it.

## Decision
- A Track can be marked **Vocal-Free**. A Vocal-Free Track **skips Separation**: it has no Instrumental and no Vocal Stem, and its Backing Audio is always the Original Audio. The Instrumental / Original Audio choice is hidden.
- The Track Pipeline goes `Downloaded → Ready` without `Separating`. **`Ready` means every step the Track needs is done**, not that stems exist. The Session does not switch to an Instrumental.
- The user marks a Track Vocal-Free with a secondary option when adding it (search result and local import; the default add stays one action) or with a toggle on Track detail at any time. Turning it on cancels a running Separation; a Track whose Separation `Failed` moves to `Ready`.
- Re-adding an existing Track with the option set opens it **unchanged**. Only the Track detail toggle changes an existing Track, so deleting stems is never hidden inside an add flow.
- Turning it on **after Separation** deletes the stems after a confirmation that names the space freed. Mixes that used the Instrumental switch to the Original Audio, which keeps them aligned because stems are sample-aligned with the Original Audio (ADR 8).
- Turning it off makes the Track behave like a newly downloaded one: `Ready → Separating(default model) → Ready`, then the Backing Audio switches to the Instrumental. Existing Mixes keep the Original Audio until the user switches them.
- The **Guide Vocal** (Session and Mix) is disabled on a Vocal-Free Track, with a short reason. Re-separation is unavailable, and the storage screen shows no stems and offers no regeneration.
- A Backing Export of a Vocal-Free Track is tagged **"Title (Instrumental, ±n)"**: the tag says what the file is, because "Original" means *with the artist's vocals* on other Tracks.
- **No automatic detection** in v1: the user always decides.

## Considered Options
- **The Original Audio counts as the Instrumental** (a copy or a reference, with no Vocal Stem). It keeps "Instrumental by default", but the Backing Audio choice would offer the same audio twice, and Mixes would reference an Instrumental "by Separation Model" that no model produced. Rejected.
- **Detecting vocal-free audio.** A title hint ("karaoke", "instrumental") is unreliable: karaoke versions with guide vocals and lyric videos contain vocals, and many karaoke uploads don't say so. Audio analysis would need Separation, which costs exactly what this decision saves. Deferred.

## Consequences
- The Track Pipeline state stays the single source of truth for "can I sing in final form" (ADR 10). The UI does not read the Vocal-Free flag to decide whether a Track is finished.
- Takes stay valid in both directions of the toggle, because they align with the Original Audio and its stems alike.
