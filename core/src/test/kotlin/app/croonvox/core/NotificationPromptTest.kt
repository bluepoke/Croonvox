// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.pipeline.NotificationPrompt
import app.croonvox.core.testing.InMemorySettingsRepository
import app.croonvox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationPromptTest {
    private val tracks = InMemoryTrackRepository()
    private val settings = InMemorySettingsRepository()
    private val id = TrackId(SourceId("local"), "song")
    private val fast = SeparationModelId("fast")

    private suspend fun addTrack(pipeline: TrackPipelineState) =
        tracks.addIfAbsent(Track(id, "Fly Me to the Moon", null, 148_000, pipeline))

    @Test
    fun `the prompt is due once a Track starts Separating`() = runTest {
        val prompt = NotificationPrompt(tracks, settings)
        addTrack(TrackPipelineState.Downloaded)
        assertEquals(false, prompt.due().first())

        tracks.updatePipeline(id, TrackPipelineState.Separating(fast))

        assertEquals(true, prompt.due().first())
    }

    @Test
    fun `once asked, the prompt is never due again, not for later Tracks nor after a restart`() = runTest {
        val prompt = NotificationPrompt(tracks, settings)
        addTrack(TrackPipelineState.Separating(fast))

        prompt.markAsked()

        assertEquals(false, prompt.due().first())
        val other = TrackId(SourceId("local"), "other")
        tracks.addIfAbsent(Track(other, "Moon River", null, 163_000, TrackPipelineState.Separating(fast)))
        assertEquals(false, prompt.due().first())
        // As after a restart: a new instance on the same stored settings.
        assertEquals(false, NotificationPrompt(tracks, settings).due().first())
    }
}
