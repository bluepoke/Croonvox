// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.port.LyricsCandidate
import app.croonvox.core.port.LyricsProvider
import app.croonvox.core.port.LyricsQuery

/**
 * In-memory [LyricsProvider]: returns candidates whose title contains the queried title and,
 * if the query has an artist, whose artist contains it.
 */
class FakeLyricsProvider : LyricsProvider {
    private val catalogue = mutableListOf<LyricsCandidate>()
    val queries = mutableListOf<LyricsQuery>()

    /** Thrown by every search while set. */
    var failWith: Exception? = null

    fun add(candidate: LyricsCandidate) {
        catalogue += candidate
    }

    override suspend fun search(query: LyricsQuery): List<LyricsCandidate> {
        queries += query
        failWith?.let { throw it }
        return catalogue.filter { candidate ->
            candidate.title.contains(query.title, ignoreCase = true) &&
                (query.artist == null || candidate.artist?.contains(query.artist, ignoreCase = true) == true)
        }
    }
}
