// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.library.TakeUsage
import app.croonvox.core.library.TrackStorage
import app.croonvox.core.model.OutputRoute
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.model.Transpose
import app.croonvox.core.testing.FakePipelineJobs
import app.croonvox.core.testing.InMemoryTakeRepository
import app.croonvox.core.testing.InMemoryTrackFiles
import app.croonvox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrackStorageTest {
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val files = InMemoryTrackFiles()
    private val jobs = FakePipelineJobs()
    private val storage = TrackStorage(tracks, takes, files, jobs, models = listOf(FAST, BEST))
    private val trackId = TrackId(SourceId("youtube"), "abc")

    private suspend fun addTrack(pipeline: TrackPipelineState, id: TrackId = trackId) =
        tracks.addIfAbsent(Track(id, "Fly Me to the Moon", "Frank Sinatra", 148_000, pipeline))

    private suspend fun keepTake(name: String, bytes: Long, track: TrackId = trackId): Take {
        val take = Take(TakeId(name), track, name, 0, 48_000, 48_000, OutputRoute.Wired, 0, Transpose.None, recordedAt = 0)
        takes.add(take)
        files.store(files.take(track, take.id), bytes)
        return take
    }

    @Test
    fun `usage is shown per Track by category, with stems per Separation Model`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        files.store(files.originalAudio(trackId), 4_000_000)
        files.store(files.stems(trackId, FAST).instrumental, 30_000_000)
        files.store(files.stems(trackId, FAST).vocalStem, 25_000_000)
        val take = keepTake("Take 1", 2_000_000)

        val usage = storage.usage().single()

        assertEquals(trackId, usage.track.id)
        assertEquals(4_000_000, usage.originalAudioBytes)
        assertEquals(mapOf(FAST to 55_000_000L), usage.stemBytes)
        assertEquals(listOf(TakeUsage(take, 2_000_000)), usage.takes)
        assertEquals(61_000_000, usage.totalBytes)
    }

    @Test
    fun `deleting a Ready Track's stems frees them and leaves the Original Audio and Takes`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        files.store(files.originalAudio(trackId), 4_000_000)
        files.store(files.stems(trackId, FAST).instrumental, 30_000_000)
        val take = keepTake("Take 1", 2_000_000)

        storage.deleteStems(trackId, FAST)

        assertEquals(TrackPipelineState.StemsDeleted(FAST), tracks.get(trackId)?.pipeline)
        val usage = storage.usage().single()
        assertEquals(emptyMap(), usage.stemBytes)
        assertEquals(4_000_000, usage.originalAudioBytes)
        assertEquals(listOf(TakeUsage(take, 2_000_000)), usage.takes)
    }

    @Test
    fun `stems can't be deleted while Separation is writing them`() = runTest {
        addTrack(TrackPipelineState.Separating(FAST))
        files.store(files.separationCheckpoint(trackId, FAST), 10_000_000)

        assertFailsWith<IllegalStateException> { storage.deleteStems(trackId, FAST) }

        assertEquals(TrackPipelineState.Separating(FAST), tracks.get(trackId)?.pipeline)
        assertEquals(emptyList(), files.deletedStems)
    }

    @Test
    fun `stems left by another Separation Model are deleted without touching the Track Pipeline`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        files.store(files.stems(trackId, BEST).instrumental, 50_000_000)

        storage.deleteStems(trackId, BEST)

        assertEquals(TrackPipelineState.Ready(FAST), tracks.get(trackId)?.pipeline)
        assertEquals(emptyMap(), storage.usage().single().stemBytes)
    }

    @Test
    fun `deleting a Take removes it and its audio and keeps the others`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        val first = keepTake("Take 1", 2_000_000)
        val second = keepTake("Take 2", 3_000_000)

        storage.deleteTake(first)

        assertEquals(listOf(TakeUsage(second, 3_000_000)), storage.usage().single().takes)
        assertEquals(listOf(files.take(trackId, first.id)), files.deletedTakes)
    }

    @Test
    fun `deleting a Track stops its jobs and removes it with everything it owns`() = runTest {
        addTrack(TrackPipelineState.Separating(FAST))
        files.store(files.originalAudio(trackId), 4_000_000)
        keepTake("Take 1", 2_000_000)
        val other = TrackId(SourceId("youtube"), "other")
        addTrack(TrackPipelineState.Ready(FAST), id = other)

        storage.deleteTrack(trackId)

        assertEquals(listOf(trackId), jobs.cancelled)
        assertEquals(null, tracks.get(trackId))
        assertEquals(emptyList(), takes.observe(trackId).first())
        assertEquals(listOf(trackId), files.deleted)
        assertEquals(listOf(other), storage.usage().map { it.track.id })
    }

    @Test
    fun `files a job wrote after its Track was deleted are cleared, other Tracks' files stay`() = runTest {
        val kept = TrackId(SourceId("youtube"), "kept")
        addTrack(TrackPipelineState.Ready(FAST), id = kept)
        files.store(files.originalAudio(kept), 4_000_000)
        // A Separation that hadn't stopped yet wrote a checkpoint for a Track that is gone.
        files.store(files.separationCheckpoint(trackId, FAST), 10_000_000)

        storage.removeOrphanedFiles()

        assertEquals(0, files.sizeOf(files.separationCheckpoint(trackId, FAST)))
        assertEquals(4_000_000, files.sizeOf(files.originalAudio(kept)))
    }

    @Test
    fun `a Vocal-Free Track shows no stems, and stems left by a late Separation are cleared at start`() = runTest {
        tracks.addIfAbsent(Track(trackId, "Karaoke", null, 148_000, TrackPipelineState.Ready(model = null), vocalFree = true))
        files.store(files.originalAudio(trackId), 4_000_000)
        files.store(files.separationCheckpoint(trackId, FAST), 10_000_000)

        assertEquals(emptyMap(), storage.usage().single().stemBytes)
        storage.removeOrphanedFiles()

        assertEquals(0, files.sizeOf(files.separationCheckpoint(trackId, FAST)))
        assertEquals(4_000_000, files.sizeOf(files.originalAudio(trackId)))
    }

    private companion object {
        val FAST = SeparationModelId("fast")
        val BEST = SeparationModelId("best")
    }
}
