// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import app.croonvox.core.model.PipelineStep
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.port.SeparationProgress

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
    /** Progress of a running step, e.g. `separating:0.42` (version 3, #7) or `downloading:0.42:1` (#6). */
    @ColumnInfo(name = "pipeline_progress") val pipelineProgress: String? = null,
    /** Why the Track is Failed (version 3, #7), or why a download waits to retry (#6). */
    @ColumnInfo(name = "pipeline_failure") val pipelineFailure: String? = null,
    /** When a Session was last opened on the Track (version 8, #20). */
    @ColumnInfo(name = "last_session_at") val lastSessionAt: Long? = null,
)

internal fun Track.toEntity(addedAt: Long): TrackEntity {
    val pipelineColumns = pipeline.toColumns()
    return TrackEntity(
        source = id.source.value,
        sourceTrackId = id.sourceTrackId,
        title = title,
        artist = artist,
        durationMs = durationMs,
        pipelineState = pipelineColumns.state,
        addedAt = addedAt,
        pipelineProgress = pipelineColumns.progress,
        pipelineFailure = pipelineColumns.failure,
        lastSessionAt = lastSessionAt,
    )
}

internal fun TrackEntity.toTrack() = Track(
    id = TrackId(SourceId(source), sourceTrackId),
    title = title,
    artist = artist,
    durationMs = durationMs,
    pipeline = PipelineColumns(pipelineState, pipelineProgress, pipelineFailure).toState(),
    lastSessionAt = lastSessionAt,
)

/** The pipeline columns of a row. Stored as stable strings so enum/class renames don't corrupt data. */
internal data class PipelineColumns(val state: String, val progress: String? = null, val failure: String? = null)

internal fun TrackPipelineState.toColumns(): PipelineColumns = when (this) {
    TrackPipelineState.Added -> PipelineColumns("added")
    // progress `downloading:<fraction or empty>:<failed attempts>`, failure the reason it waits to retry
    is TrackPipelineState.Downloading ->
        PipelineColumns("downloading", progress = "downloading:${progress ?: ""}:$failedAttempts", failure = retryReason)
    TrackPipelineState.Downloaded -> PipelineColumns("downloaded")
    is TrackPipelineState.Separating -> PipelineColumns("$SEPARATING_PREFIX${model.value}", progress = progress?.toColumn())
    is TrackPipelineState.Ready -> PipelineColumns("$READY_PREFIX${model.value}")
    is TrackPipelineState.StemsDeleted -> PipelineColumns("$STEMS_DELETED_PREFIX${model.value}")
    is TrackPipelineState.Failed -> PipelineColumns("$FAILED_PREFIX${step.toColumn()}", failure = reason)
}

private fun PipelineColumns.toState(): TrackPipelineState = when {
    state == "added" -> TrackPipelineState.Added
    state == "downloading" -> {
        val parts = progress?.split(':').orEmpty()
        TrackPipelineState.Downloading(
            progress = parts.getOrNull(1)?.toFloatOrNull(),
            retryReason = failure,
            failedAttempts = parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
    }
    state == "downloaded" -> TrackPipelineState.Downloaded
    state.startsWith(SEPARATING_PREFIX) ->
        TrackPipelineState.Separating(SeparationModelId(state.removePrefix(SEPARATING_PREFIX)), progress?.let(::progressFromColumn))
    state.startsWith(READY_PREFIX) -> TrackPipelineState.Ready(SeparationModelId(state.removePrefix(READY_PREFIX)))
    state.startsWith(STEMS_DELETED_PREFIX) ->
        TrackPipelineState.StemsDeleted(SeparationModelId(state.removePrefix(STEMS_DELETED_PREFIX)))
    state.startsWith(FAILED_PREFIX) ->
        TrackPipelineState.Failed(stepFromColumn(state.removePrefix(FAILED_PREFIX)), failure.orEmpty())
    else -> error("unknown pipeline state '$state'")
}

private fun SeparationProgress.toColumn(): String = when (stage) {
    SeparationProgress.Stage.DownloadingModel -> "downloading_model"
    SeparationProgress.Stage.InstallingModel -> "installing_model"
    SeparationProgress.Stage.Separating -> "separating"
} + ":$fraction"

private fun progressFromColumn(value: String): SeparationProgress {
    val stage = when (value.substringBefore(':')) {
        "downloading_model" -> SeparationProgress.Stage.DownloadingModel
        "installing_model" -> SeparationProgress.Stage.InstallingModel
        "separating" -> SeparationProgress.Stage.Separating
        else -> error("unknown progress '$value'")
    }
    return SeparationProgress(stage, value.substringAfter(':').toFloat())
}

private fun PipelineStep.toColumn(): String = when (this) {
    PipelineStep.Downloading -> "downloading"
    PipelineStep.Separating -> "separating"
}

private fun stepFromColumn(value: String): PipelineStep = when (value) {
    "downloading" -> PipelineStep.Downloading
    "separating" -> PipelineStep.Separating
    else -> error("unknown pipeline step '$value'")
}

private const val SEPARATING_PREFIX = "separating:"
private const val READY_PREFIX = "ready:"
private const val FAILED_PREFIX = "failed:"
private const val STEMS_DELETED_PREFIX = "stems_deleted:"
