// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.port

import app.croonvox.core.model.SourceId
import app.croonvox.core.model.TrackId

/**
 * A provider of songs: search → candidates → resolve to media (GLOSSARY: Source, ADR 1).
 * One instance per service (YouTube, SoundCloud, …); see [SourceId].
 */
interface Source {
    val id: SourceId

    suspend fun search(query: String): List<SourceCandidate>

    /** Where the media for [sourceTrackId] can be read from right now (stream URL or file). */
    suspend fun resolve(sourceTrackId: String): ResolvedMedia
}

/** One search result of a [Source]. */
data class SourceCandidate(
    val id: TrackId,
    val title: String,
    val artist: String?,
    val durationMs: Long?,
)

/** Readable locations of a candidate's media, as URIs the platform layer understands. */
data class ResolvedMedia(val audioUri: String, val videoUri: String?)
