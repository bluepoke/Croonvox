// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.library.StagedFile
import app.croonvox.core.library.TrackFiles
import app.croonvox.core.model.AppDirectory
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.TrackId
import app.croonvox.core.port.Stems
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

    override fun stems(track: TrackId, model: SeparationModelId) = Stems(
        instrumental = AudioFile("/mem/${track.key}/${model.value}/instrumental"),
        vocalStem = AudioFile("/mem/${track.key}/${model.value}/vocals"),
    )

    override fun separationCheckpoint(track: TrackId, model: SeparationModelId) =
        AppDirectory("/mem/${track.key}/${model.value}/checkpoint")

    override fun take(track: TrackId, take: TakeId) = AudioFile("/mem/${track.key}/takes/${take.value}")

    /** Take files removed through [deleteTake]. */
    val deletedTakes = mutableListOf<AudioFile>()

    override suspend fun deleteTake(track: TrackId, take: TakeId) {
        deletedTakes += take(track, take)
    }

    override suspend fun stage(content: InputStream): StagedFile {
        val bytes = content.readBytes()
        val file = AudioFile("/mem/staging/${nextStage++}")
        staged[file] = bytes
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return StagedFile(file, sha256)
    }

    private val parts = mutableMapOf<AudioFile, ByteArray>()

    override fun downloadPart(track: TrackId, audioKey: String) = AudioFile("/mem/${track.key}/download-$audioKey")

    /** What a download stored so far in [part]. */
    fun partContent(part: AudioFile): ByteArray = parts[part] ?: ByteArray(0)

    /** Adds [bytes] to the end of [part], as a resumed download does. */
    fun appendToPart(part: AudioFile, bytes: ByteArray) {
        parts[part] = partContent(part) + bytes
    }

    override suspend fun discardOtherDownloads(track: TrackId, keep: AudioFile) {
        parts.keys.removeAll { it != keep && it.path.startsWith("/mem/${track.key}/download-") }
    }

    override suspend fun commitDownload(track: TrackId, part: AudioFile) {
        originals[track] = checkNotNull(parts.remove(part)) { "no download in $part" }
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
