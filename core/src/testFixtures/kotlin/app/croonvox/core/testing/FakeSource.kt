// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.model.SourceId
import app.croonvox.core.model.TrackId
import app.croonvox.core.port.ResolvedMedia
import app.croonvox.core.port.Source
import app.croonvox.core.port.SourceCandidate

/** In-memory [Source]: search matches the query against title and artist, case-insensitively. */
class FakeSource(override val id: SourceId) : Source {
    private val catalogue = mutableListOf<SourceCandidate>()

    fun add(sourceTrackId: String, title: String, artist: String? = null, durationMs: Long? = null): SourceCandidate =
        SourceCandidate(TrackId(id, sourceTrackId), title, artist, durationMs).also { catalogue += it }

    override suspend fun search(query: String): List<SourceCandidate> =
        catalogue.filter { c ->
            c.title.contains(query, ignoreCase = true) || c.artist?.contains(query, ignoreCase = true) == true
        }

    override suspend fun resolve(sourceTrackId: String): ResolvedMedia {
        require(catalogue.any { it.id.sourceTrackId == sourceTrackId }) { "unknown $sourceTrackId" }
        return ResolvedMedia(audioUri = "fake://${TrackId(id, sourceTrackId).key}/audio", videoUri = null)
    }
}
