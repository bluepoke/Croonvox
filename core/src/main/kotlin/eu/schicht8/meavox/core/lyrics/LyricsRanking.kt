// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.lyrics

import eu.schicht8.meavox.core.port.LyricsCandidate
import eu.schicht8.meavox.core.port.LyricsQuery
import kotlin.math.abs

/**
 * Orders Lyrics candidates best first (ADR 6). The right artist and title come first: a cover of
 * the same length is the wrong song. The duration then tells versions apart (album, live, radio
 * edit; a video may run a few seconds longer). Among equals, synced Lyrics beat plain ones because
 * they also work in the Karaoke View.
 */
object LyricsRanking {
    fun rank(candidates: List<LyricsCandidate>, query: LyricsQuery): List<LyricsCandidate> =
        candidates.withIndex()
            .sortedWith(compareByDescending<IndexedValue<LyricsCandidate>> { score(it.value, query) }.thenBy { it.index })
            .map { it.value }

    private fun score(candidate: LyricsCandidate, query: LyricsQuery): Int {
        var score = 0
        val wanted = query.durationMs
        val found = candidate.durationMs
        if (wanted != null && found != null) {
            val diff = abs(wanted - found)
            score += when {
                diff <= 3_000 -> 4
                diff <= 10_000 -> 2
                else -> 0
            }
        }
        if (normalize(candidate.title) == normalize(query.title)) score += 4
        if (query.artist != null && candidate.artist != null && normalize(candidate.artist) == normalize(query.artist)) score += 4
        if (candidate.synced != null) score += 1
        return score
    }

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
}
