// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.model

/** A song the user added (GLOSSARY: Track), identified by its Source and source ID (ADR 10). */
data class Track(
    val id: TrackId,
    val title: String,
    val artist: String?,
    val durationMs: Long?,
    val pipeline: TrackPipelineState,
)

/** Track identity: the Source plus that Source's own ID for the item (ADR 10). */
data class TrackId(val source: SourceId, val sourceTrackId: String) {
    /** Canonical string form `source:sourceTrackId` (ADR 10), e.g. `youtube:dQw4w9WgXcQ`. */
    val key: String get() = "${source.value}:$sourceTrackId"
}

/**
 * Stable name of one Source, e.g. `youtube`, `soundcloud` or `local`. Each service behind the
 * NewPipe adapter is its own Source, so the ID namespace is the service, not the library.
 */
@JvmInline
value class SourceId(val value: String)

/** Where a Track is in its processing (GLOSSARY: Track Pipeline, ADR 10). */
sealed interface TrackPipelineState {
    /** Known to the Library; nothing downloaded yet. */
    data object Added : TrackPipelineState
}
