// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState

/** Row of the `tracks` table. Primary key is the Track identity (ADR 10). */
@Entity(tableName = "tracks", primaryKeys = ["source", "source_track_id"])
internal data class TrackEntity(
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "source_track_id") val sourceTrackId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "pipeline_state") val pipelineState: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

internal fun Track.toEntity(addedAt: Long) = TrackEntity(
    source = id.source.value,
    sourceTrackId = id.sourceTrackId,
    title = title,
    artist = artist,
    durationMs = durationMs,
    pipelineState = pipeline.toColumn(),
    addedAt = addedAt,
)

internal fun TrackEntity.toTrack() = Track(
    id = TrackId(SourceId(source), sourceTrackId),
    title = title,
    artist = artist,
    durationMs = durationMs,
    pipeline = pipelineStateFromColumn(pipelineState),
)

// Stored as stable strings so enum/class renames don't corrupt data.
internal fun TrackPipelineState.toColumn(): String = when (this) {
    TrackPipelineState.Added -> "added"
    TrackPipelineState.Downloaded -> "downloaded"
    is TrackPipelineState.Ready -> "$READY_PREFIX${model.value}"
}

private fun pipelineStateFromColumn(value: String): TrackPipelineState = when {
    value == "added" -> TrackPipelineState.Added
    value == "downloaded" -> TrackPipelineState.Downloaded
    value.startsWith(READY_PREFIX) -> TrackPipelineState.Ready(SeparationModelId(value.removePrefix(READY_PREFIX)))
    else -> error("unknown pipeline state '$value'")
}

private const val READY_PREFIX = "ready:"
