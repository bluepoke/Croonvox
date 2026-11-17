// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.library.ImportLocalFile
import app.croonvox.core.library.Library
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.testing.FakeLocalFile
import app.croonvox.core.testing.InMemoryTrackFiles
import app.croonvox.core.testing.InMemoryTakeRepository
import app.croonvox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ImportLocalFileTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val import = ImportLocalFile(tracks, files)
    private val library = Library(tracks, InMemoryTakeRepository())

    @Test
    fun `importing a file adds a Downloaded Track from the Local File Source named after the file`() = runTest {
        val track = import(FakeLocalFile(displayName = "My Way (demo).mp3", content = "audio-1"))

        assertEquals(SourceId.Local, track.id.source)
        assertEquals("My Way (demo)", track.title)
        assertEquals(TrackPipelineState.Downloaded, track.pipeline)
        assertEquals(listOf(track), library.tracks().first())
    }

    @Test
    fun `re-importing the same content opens the existing Track instead of adding a duplicate`() = runTest {
        val first = import(FakeLocalFile(displayName = "take-five.flac", content = "audio-1"))

        val again = import(FakeLocalFile(displayName = "Take Five (copy).flac", content = "audio-1"))

        assertEquals(first, again)
        assertEquals(listOf(first), library.tracks().first())
        assertEquals(emptySet(), files.pendingStaged)
    }

    @Test
    fun `files with the same name but different content become different Tracks`() = runTest {
        val a = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        val b = import(FakeLocalFile(displayName = "song.mp3", content = "audio-2"))

        assertNotEquals(a.id, b.id)
        assertEquals(2, library.tracks().first().size)
    }

    @Test
    fun `re-importing completes an import that was interrupted before the file was stored`() = runTest {
        val content = "audio-1"
        val interrupted = tracks.addIfAbsent(
            Track(TrackId(SourceId.Local, sha256(content)), "lost", null, null, TrackPipelineState.Added),
        )

        val track = import(FakeLocalFile(displayName = "found.wav", content = content))

        assertEquals(interrupted.id, track.id)
        assertEquals(TrackPipelineState.Downloaded, track.pipeline)
        assertEquals(content, files.originalAudioContent(track.id)?.decodeToString())
    }

    private fun sha256(text: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
