// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.mix.ExportMix
import app.croonvox.core.mix.Mix
import app.croonvox.core.mix.MixId
import app.croonvox.core.mix.MixSettings
import app.croonvox.core.mix.MixUnavailable
import app.croonvox.core.model.OutputRoute
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.model.Transpose
import app.croonvox.core.port.ExportFormat
import app.croonvox.core.port.MixRender
import app.croonvox.core.session.BackingAudio
import app.croonvox.core.testing.FakeExportStore
import app.croonvox.core.testing.FakeOfflineRenderer
import app.croonvox.core.testing.InMemoryMixRepository
import app.croonvox.core.testing.InMemoryTakeRepository
import app.croonvox.core.testing.InMemoryTrackFiles
import app.croonvox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportMixTest {
    private val trackId = TrackId(SourceId("fake"), "v1")
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val mixes = InMemoryMixRepository()
    private val files = InMemoryTrackFiles()
    private val renderer = FakeOfflineRenderer()
    private val store = FakeExportStore()
    private val export = ExportMix(tracks, takes, mixes, files, renderer, store)

    // Sung from 0:30 for 10 s at +2, with the engine's latency estimate at 100 ms.
    private val take = Take(
        id = TakeId("t1"), track = trackId, name = "Take 1",
        startFrame = 1_440_000, frames = 480_000, sampleRate = 48_000,
        route = OutputRoute.Wired, reportedLatencyFrames = 4_800, transpose = Transpose(2), recordedAt = 0,
    )

    private suspend fun arrange(
        pipeline: TrackPipelineState,
        backing: BackingAudio,
        settings: MixSettings = MixSettings(backingDb = -12f, vocalDb = 3f, vocalNudgeMs = 25),
        vocalFree: Boolean = false,
    ): Mix {
        tracks.addIfAbsent(Track(trackId, "Fly Me to the Moon", "Frank Sinatra", 180_000, pipeline, vocalFree = vocalFree))
        takes.add(take)
        return Mix(MixId("m1"), trackId, take.id, "Mix 1", backing, settings, createdAt = 0).also { mixes.add(it) }
    }

    @Test
    fun `an Export renders the Take's span over its Backing Audio with the Take's Transpose, calibration, Nudge and levels`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(FAST), BackingAudio.Instrumental(FAST))
        val progress = mutableListOf<Float>()

        val exported = export.export(mix.id, ExportFormat.Wav(24)) { progress += it }

        // A Nudge of +25 ms moves the voice 1 200 frames later: 4 800 − 1 200 = 3 600 frames of alignment.
        // The Mix ends where Review would: once the last Take sample has sounded.
        assertEquals(
            MixRender(
                backing = files.stems(trackId, FAST).instrumental,
                take = files.take(trackId, take.id),
                sampleRate = 48_000,
                takeStartFrame = 1_440_000,
                alignmentFrames = 3_600,
                transpose = Transpose(2),
                backingGainDb = -12f,
                takeGainDb = 3f,
                fromFrame = 1_440_000,
                toFrame = 1_916_400,
            ),
            renderer.renders.single(),
        )
        val written = store.exports.single()
        assertEquals("Fly Me to the Moon (Mix 1)", written.title)
        assertEquals(ExportFormat.Wav(24), written.format)
        assertEquals(48_000, written.sampleRate)
        assertEquals(476_400, written.frames)
        assertEquals(476_400, written.framesWritten)
        assertTrue(written.committed)
        assertEquals("content://exports/0", exported.uri)
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `a Mix over the Original Audio renders the Original Audio`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(FAST), BackingAudio.OriginalAudio)

        export.export(mix.id, ExportFormat.Wav())

        assertEquals(files.originalAudio(trackId), renderer.renders.single().backing)
    }

    @Test
    fun `cancelling an Export removes the unfinished file`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(FAST), BackingAudio.Instrumental(FAST))
        renderer.pauseAfterFirstBlock = true

        val job = launch { export.export(mix.id, ExportFormat.Wav()) }
        runCurrent()
        job.cancel()
        runCurrent()

        val written = store.exports.single()
        assertTrue(written.aborted)
        assertFalse(written.committed)
    }

    @Test
    fun `a Mix can't be exported while its Instrumental is being made again`() = runTest {
        val mix = arrange(TrackPipelineState.StemsDeleted(FAST), BackingAudio.Instrumental(FAST))

        assertFailsWith<MixUnavailable> { export.export(mix.id, ExportFormat.Wav()) }
        assertEquals(emptyList(), store.exports)
    }

    @Test
    fun `a Mix whose Instrumental is gone after re-separation switches to the new Instrumental`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(BEST), BackingAudio.Instrumental(FAST))

        export.export(mix.id, ExportFormat.Wav())

        assertEquals(files.stems(trackId, BEST).instrumental, renderer.renders.single().backing)
        assertEquals(BackingAudio.Instrumental(BEST), mixes.get(mix.id)?.backing)
    }

    @Test
    fun `a Mix keeps its Instrumental after re-separation while those stems are still stored`() = runTest {
        val mix = arrange(TrackPipelineState.Ready(BEST), BackingAudio.Instrumental(FAST))
        files.store(files.stems(trackId, FAST).instrumental, 30_000_000)

        export.export(mix.id, ExportFormat.Wav())

        assertEquals(files.stems(trackId, FAST).instrumental, renderer.renders.single().backing)
        assertEquals(BackingAudio.Instrumental(FAST), mixes.get(mix.id)?.backing)
    }

    private companion object {
        val FAST = SeparationModelId("fast")
        val BEST = SeparationModelId("best")
    }
}
