# 17. Deleted stems come back when the Track is next opened

- Status: Accepted (amends ADR 10)
- Date: 2026-11-17

## Context
The storage screen (ADR 8, #20) lets the user delete a Track's stems to free space, because they can be made again from the Original Audio. A Downloaded Track is separated right away (ADR 10), so a Track that simply went back to Downloaded would be separated again at once, and the space would never be freed.

## Decision
- Deleting the stems of a Ready Track moves it to a new Track Pipeline state, **Stems Deleted**, which remembers the Separation Model. The Track Pipeline doesn't queue it.
- **Opening the Track** on Track detail or in a Session moves it back to `Separating` with the same model. The Original Audio plays meanwhile, and the usual switch to the Instrumental happens once it is `Ready`.
- Stems can't be deleted while Separation with that model is running.

## Considered Options
- **Separate again only when a Session opens.** Track detail would keep playing the Original Audio. Rejected: playing the Track is a use too, and the switch to the Instrumental already works there.
- **An explicit "Regenerate" button.** Rejected: the user would have to remember it before singing.

## Consequences
- The Track Pipeline is `Added → Downloading → Downloaded → Separating(model) → Ready / Failed`, plus `Ready → Stems Deleted → Separating(model)`.
- Takes stay aligned, because stems are sample-aligned with the Original Audio (ADR 8).
- When Vocal-Free Tracks (ADR 14, #25) are built, they have no stems, so they never reach Stems Deleted.
