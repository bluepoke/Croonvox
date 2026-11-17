// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.library.ImportLocalFile
import app.croonvox.core.model.PipelineStep
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.pipeline.TrackPipeline
import app.croonvox.core.port.SeparationProgress
import app.croonvox.core.testing.FakeLocalFile
import app.croonvox.core.testing.FakePipelineJobs
import app.croonvox.core.testing.InMemoryTrackFiles
import app.croonvox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TrackPipelineTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val jobs = FakePipelineJobs()
    private val import = ImportLocalFile(tracks, files)
    private val fast = SeparationModelId("fast")
    private val pipeline = TrackPipeline(tracks, jobs, defaultModel = fast)

    private fun TestScope.startPipeline() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.run() }
    }

    @Test
    fun `a Downloaded Track is queued for Separation with the default model`() = runTest {
        startPipeline()

        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))

        assertEquals(listOf(FakePipelineJobs.Separation(track.id, fast)), jobs.separations)
        assertEquals(TrackPipelineState.Separating(fast), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `at start a Track left Separating by an earlier process is queued again once with its model`() = runTest {
        val best = SeparationModelId("best")
        val interrupted = import(FakeLocalFile(displayName = "a.mp3", content = "audio-a"))
        tracks.updatePipeline(interrupted.id, TrackPipelineState.Separating(best, SeparationProgress(SeparationProgress.Stage.Separating, 0.3f)))
        val ready = import(FakeLocalFile(displayName = "b.mp3", content = "audio-b"))
        tracks.updatePipeline(ready.id, TrackPipelineState.Ready(fast))
        val failed = import(FakeLocalFile(displayName = "c.mp3", content = "audio-c"))
        tracks.updatePipeline(failed.id, TrackPipelineState.Failed(PipelineStep.Separating, "broken file"))

        startPipeline()
        // The running job reports progress, which changes the Track again.
        tracks.updatePipeline(interrupted.id, TrackPipelineState.Separating(best, SeparationProgress(SeparationProgress.Stage.Separating, 0.4f)))

        assertEquals(listOf(FakePipelineJobs.Separation(interrupted.id, best)), jobs.separations)
        assertEquals(TrackPipelineState.Ready(fast), tracks.get(ready.id)?.pipeline)
        assertEquals(TrackPipelineState.Failed(PipelineStep.Separating, "broken file"), tracks.get(failed.id)?.pipeline)
    }

    @Test
    fun `retrying a Track whose Separation failed queues it again with the default model`() = runTest {
        startPipeline()
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.Failed(PipelineStep.Separating, "out of memory"))

        pipeline.retry(track.id)

        assertEquals(List(2) { FakePipelineJobs.Separation(track.id, fast) }, jobs.separations)
        assertEquals(TrackPipelineState.Separating(fast), tracks.get(track.id)?.pipeline)
    }
}
