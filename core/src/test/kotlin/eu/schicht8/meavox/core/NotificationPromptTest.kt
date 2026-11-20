// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.pipeline.NotificationPrompt
import eu.schicht8.meavox.core.testing.InMemorySettingsRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
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
