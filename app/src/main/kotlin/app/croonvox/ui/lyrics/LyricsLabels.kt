// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.lyrics

import app.croonvox.core.port.LyricsCandidate
import app.croonvox.ui.formatTime

/** "Synced" (works in the Karaoke View too) or "Plain" (Page View only), ADR 6. */
fun LyricsCandidate.kind(): String = if (synced != null) "Synced" else "Plain"

/** "Artist – Title" of a Lyrics match. */
fun LyricsCandidate.name(): String = listOfNotNull(artist, title).joinToString(" – ")

/** "Synced · 4:18": what the match is, for comparing it with the Track. */
fun LyricsCandidate.details(): String = listOfNotNull(kind(), durationMs?.let(::formatTime)).joinToString(" · ")
