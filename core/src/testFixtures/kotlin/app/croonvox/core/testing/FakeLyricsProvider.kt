// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.port.LyricsCandidate
import app.croonvox.core.port.LyricsProvider
import app.croonvox.core.port.LyricsQuery

/** In-memory [LyricsProvider]: returns candidates whose title contains the queried title. */
class FakeLyricsProvider : LyricsProvider {
    private val catalogue = mutableListOf<LyricsCandidate>()
    val queries = mutableListOf<LyricsQuery>()

    fun add(candidate: LyricsCandidate) {
        catalogue += candidate
    }

    override suspend fun search(query: LyricsQuery): List<LyricsCandidate> {
        queries += query
        return catalogue.filter { it.title.contains(query.title, ignoreCase = true) }
    }
}
