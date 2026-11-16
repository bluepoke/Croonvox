// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.croonvox.core.model.OutputRoute
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.TrackId

/** Row of the `takes` table. A Take belongs to one Track and goes when the Track goes. */
@Entity(
    tableName = "takes",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["source", "source_track_id"],
            childColumns = ["source", "source_track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("source", "source_track_id")],
)
internal data class TakeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "source_track_id") val sourceTrackId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "start_frame") val startFrame: Long,
    @ColumnInfo(name = "frames") val frames: Long,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int,
    @ColumnInfo(name = "output_route") val outputRoute: String,
    @ColumnInfo(name = "reported_latency_frames") val reportedLatencyFrames: Long,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
)

internal fun Take.toEntity() = TakeEntity(
    id = id.value,
    source = track.source.value,
    sourceTrackId = track.sourceTrackId,
    name = name,
    startFrame = startFrame,
    frames = frames,
    sampleRate = sampleRate,
    outputRoute = route.toColumn(),
    reportedLatencyFrames = reportedLatencyFrames,
    recordedAt = recordedAt,
)

internal fun TakeEntity.toTake() = Take(
    id = TakeId(id),
    track = TrackId(SourceId(source), sourceTrackId),
    name = name,
    startFrame = startFrame,
    frames = frames,
    sampleRate = sampleRate,
    route = outputRouteFromColumn(outputRoute),
    reportedLatencyFrames = reportedLatencyFrames,
    recordedAt = recordedAt,
)

// Stored as stable strings so enum renames don't corrupt data.
private fun OutputRoute.toColumn(): String = when (this) {
    OutputRoute.Speaker -> "speaker"
    OutputRoute.Wired -> "wired"
    OutputRoute.Usb -> "usb"
    OutputRoute.Bluetooth -> "bluetooth"
    OutputRoute.Other -> "other"
}

private fun outputRouteFromColumn(value: String): OutputRoute = when (value) {
    "speaker" -> OutputRoute.Speaker
    "wired" -> OutputRoute.Wired
    "usb" -> OutputRoute.Usb
    "bluetooth" -> OutputRoute.Bluetooth
    else -> OutputRoute.Other
}
