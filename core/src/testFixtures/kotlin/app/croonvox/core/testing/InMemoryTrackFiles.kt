// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.library.StagedFile
import app.croonvox.core.library.TrackFiles
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.TrackId
import java.io.InputStream
import java.security.MessageDigest

/** [TrackFiles] with synthetic paths and in-memory content; records staging, commits and deletes. */
class InMemoryTrackFiles : TrackFiles {
    private var nextStage = 0
    private val staged = mutableMapOf<AudioFile, ByteArray>()
    private val originals = mutableMapOf<TrackId, ByteArray>()
    val deleted = mutableListOf<TrackId>()

    /** Staged files that were neither committed nor discarded. */
    val pendingStaged: Set<AudioFile> get() = staged.keys

    fun originalAudioContent(track: TrackId): ByteArray? = originals[track]

    override fun originalAudio(track: TrackId) = AudioFile("/mem/${track.key}/original")

    override suspend fun stage(content: InputStream): StagedFile {
        val bytes = content.readBytes()
        val file = AudioFile("/mem/staging/${nextStage++}")
        staged[file] = bytes
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return StagedFile(file, sha256)
    }

    override suspend fun commitOriginalAudio(staged: StagedFile, track: TrackId) {
        originals[track] = checkNotNull(this.staged.remove(staged.file)) { "not staged: ${staged.file}" }
    }

    override suspend fun discard(staged: StagedFile) {
        this.staged.remove(staged.file)
    }

    override suspend fun discardAbandonedStaging() {
        staged.clear()
    }

    override suspend fun deleteAll(track: TrackId) {
        originals.remove(track)
        deleted += track
    }
}
