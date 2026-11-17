// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.library.ImportLocalFile
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.player.NotPlayableYet
import app.croonvox.core.player.TrackPlayer
import app.croonvox.core.port.Playback
import app.croonvox.core.testing.FakeAudioEngine
import app.croonvox.core.testing.FakeLocalFile
import app.croonvox.core.testing.InMemoryTrackFiles
import app.croonvox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackPlayerTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val engine = FakeAudioEngine()
    private val import = ImportLocalFile(tracks, files)
    private val player = TrackPlayer(tracks, files, engine)

    @Test
    fun `opening a Downloaded Track loads its Original Audio and plays it`() = runTest {
        val track = import(FakeLocalFile(displayName = "blue-moon.mp3", content = "audio-1"))

        val opened = player.open(track.id)
        opened.play()

        assertEquals(files.originalAudio(track.id), player.playback.value.loaded)
        assertTrue(player.playback.value.playing)
    }

    @Test
    fun `a Track whose Original Audio is not stored yet cannot be opened`() = runTest {
        val added = tracks.addIfAbsent(
            Track(TrackId(SourceId("fake"), "v1"), "Not here yet", null, null, TrackPipelineState.Added),
        )

        assertFailsWith<NotPlayableYet> { player.open(added.id) }
        assertEquals(Playback.Idle, player.playback.value)
    }

    @Test
    fun `closing an opened Track unloads its audio`() = runTest {
        val track = import(FakeLocalFile(displayName = "blue-moon.mp3", content = "audio-1"))
        val opened = player.open(track.id)
        opened.play()

        opened.close()

        assertEquals(Playback.Idle, player.playback.value)
    }

    @Test
    fun `closing an earlier Track does not unload a Track opened after it`() = runTest {
        val first = import(FakeLocalFile(displayName = "a.mp3", content = "audio-a"))
        val second = import(FakeLocalFile(displayName = "b.mp3", content = "audio-b"))
        val openedFirst = player.open(first.id)
        player.open(second.id)

        openedFirst.close() // e.g. the old Track detail screen is torn down after the new one opened

        assertEquals(files.originalAudio(second.id), player.playback.value.loaded)
    }

    @Test
    fun `opening a Track reports load progress up to completion`() = runTest {
        val track = import(FakeLocalFile(displayName = "long-song.mp3", content = "audio-1"))
        val progress = mutableListOf<Float>()

        player.open(track.id) { progress += it }

        assertTrue(progress.isNotEmpty())
        assertEquals(1f, progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun `a Ready Track plays its Instrumental, a Track still Separating its Original Audio`() = runTest {
        val model = SeparationModelId("fast")
        val ready = import(FakeLocalFile(displayName = "a.mp3", content = "audio-a"))
        tracks.updatePipeline(ready.id, TrackPipelineState.Ready(model))
        val separating = import(FakeLocalFile(displayName = "b.mp3", content = "audio-b"))
        tracks.updatePipeline(separating.id, TrackPipelineState.Separating(model))

        player.open(ready.id)
        val readyLoaded = player.playback.value.loaded
        player.open(separating.id)

        assertEquals(files.stems(ready.id, model).instrumental, readyLoaded)
        assertEquals(files.originalAudio(separating.id), player.playback.value.loaded)
    }
}
