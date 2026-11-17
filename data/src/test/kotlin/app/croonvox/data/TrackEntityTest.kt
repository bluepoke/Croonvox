// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import app.croonvox.core.model.PipelineStep
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.port.SeparationProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackEntityTest {
    private val model = SeparationModelId("spleeter-2stems")

    private fun track(state: TrackPipelineState) =
        Track(TrackId(SourceId("youtube"), "abc"), "Song", "Artist", 1_000, state)

    @Test
    fun `every Track Pipeline state is stored and read back unchanged`() {
        val states = listOf(
            TrackPipelineState.Added,
            TrackPipelineState.Downloading(),
            TrackPipelineState.Downloading(0.42f),
            TrackPipelineState.Downloading(0.42f, retryReason = "the connection broke", failedAttempts = 2),
            TrackPipelineState.Downloading(null, retryReason = "no network", failedAttempts = 1),
            TrackPipelineState.Downloaded,
            TrackPipelineState.Separating(model),
            TrackPipelineState.Separating(model, SeparationProgress(SeparationProgress.Stage.DownloadingModel, 0.25f)),
            TrackPipelineState.Separating(model, SeparationProgress(SeparationProgress.Stage.InstallingModel, 0.75f)),
            TrackPipelineState.Separating(model, SeparationProgress(SeparationProgress.Stage.Separating, 0.5f)),
            TrackPipelineState.Ready(model),
            TrackPipelineState.StemsDeleted(model),
            TrackPipelineState.Ready(model = null),
            TrackPipelineState.Failed(PipelineStep.Separating, "no decoder for audio/x-weird"),
            TrackPipelineState.Failed(PipelineStep.Downloading, "HTTP 404"),
        )
        for (state in states) {
            assertEquals(track(state), track(state).toEntity(addedAt = 1).toTrack(), "$state")
        }
    }

    @Test
    fun `states stored by earlier versions still read`() {
        val earlier = track(TrackPipelineState.Ready(model)).toEntity(addedAt = 1)
            .copy(pipelineState = "ready:fast", pipelineProgress = null, pipelineFailure = null)

        assertEquals(TrackPipelineState.Ready(SeparationModelId("fast")), earlier.toTrack().pipeline)
    }
}
