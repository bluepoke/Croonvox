// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import app.croonvox.core.library.StagedFile
import app.croonvox.core.library.TrackFiles
import app.croonvox.core.model.AppDirectory
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.TrackId
import app.croonvox.core.port.Stems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * [TrackFiles] under an app-private [root] (e.g. `filesDir/tracks`). Each Track gets one
 * directory named by a hash of its identity, so source IDs never need escaping. Staging files
 * live in the same file system, so committing is an atomic rename.
 */
class AppTrackFiles(private val root: File) : TrackFiles {
    private val stagingDir = File(root, ".staging")

    override fun originalAudio(track: TrackId): AudioFile = AudioFile(File(dirOf(track), "original").path)

    // Model IDs are catalogue names (e.g. "fast"); hashed like Track keys so they need no escaping.
    override fun stems(track: TrackId, model: SeparationModelId): Stems {
        val dir = File(dirOf(track), "stems/${hash(model.value)}")
        return Stems(AudioFile(File(dir, "instrumental.flac").path), AudioFile(File(dir, "vocals.flac").path))
    }

    override fun separationCheckpoint(track: TrackId, model: SeparationModelId): AppDirectory =
        AppDirectory(File(dirOf(track), "stems/${hash(model.value)}.checkpoint").path)

    override fun take(track: TrackId, take: TakeId): AudioFile =
        AudioFile(File(dirOf(track), "takes/${hash(take.value)}.flac").path)

    override suspend fun deleteStems(track: TrackId, model: SeparationModelId) {
        withContext(Dispatchers.IO) {
            val stems = stems(track, model)
            File(stems.instrumental.path).parentFile?.deleteRecursively()
            File(separationCheckpoint(track, model).path).deleteRecursively()
        }
    }

    override suspend fun sizeOf(file: AudioFile): Long = withContext(Dispatchers.IO) { File(file.path).length() }

    override suspend fun sizeOf(directory: AppDirectory): Long = withContext(Dispatchers.IO) {
        File(directory.path).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    override suspend fun deleteTake(track: TrackId, take: TakeId) {
        withContext(Dispatchers.IO) { File(take(track, take).path).delete() }
    }

    override suspend fun stage(content: InputStream): StagedFile = withContext(Dispatchers.IO) {
        stagingDir.mkdirs()
        val file = File(stagingDir, UUID.randomUUID().toString())
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestOutputStream(file.outputStream().buffered(), digest).use { out -> content.copyTo(out) }
        } catch (e: Throwable) { // includes cancellation
            file.delete()
            throw e
        }
        StagedFile(AudioFile(file.path), digest.digest().toHex())
    }

    override fun downloadPart(track: TrackId, audioKey: String): AudioFile =
        AudioFile(File(dirOf(track), "download-${hash(audioKey)}.part").path)

    override suspend fun discardOtherDownloads(track: TrackId, keep: AudioFile) {
        withContext(Dispatchers.IO) {
            dirOf(track).listFiles { f -> f.name.startsWith("download-") && f.path != keep.path }?.forEach { it.delete() }
        }
    }

    override suspend fun commitDownload(track: TrackId, part: AudioFile) = withContext(Dispatchers.IO) {
        val target = File(originalAudio(track).path)
        target.parentFile?.mkdirs()
        if (!File(part.path).renameTo(target)) throw IOException("could not move ${part.path} to $target")
    }

    override suspend fun commitOriginalAudio(staged: StagedFile, track: TrackId) = withContext(Dispatchers.IO) {
        val target = File(originalAudio(track).path)
        target.parentFile?.mkdirs()
        if (!File(staged.file.path).renameTo(target)) throw IOException("could not move ${staged.file.path} to $target")
    }

    override suspend fun discard(staged: StagedFile) {
        withContext(Dispatchers.IO) { File(staged.file.path).delete() }
    }

    override suspend fun discardAbandonedStaging() {
        withContext(Dispatchers.IO) { stagingDir.listFiles()?.forEach { it.delete() } }
    }

    override suspend fun deleteAll(track: TrackId) {
        withContext(Dispatchers.IO) { dirOf(track).deleteRecursively() }
    }

    override suspend fun deleteAllExcept(keep: Set<TrackId>) {
        withContext(Dispatchers.IO) {
            val kept = keep.mapTo(HashSet()) { dirOf(it).name }
            // Hidden directories (staging) are not Tracks.
            root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f.name !in kept }?.forEach { it.deleteRecursively() }
        }
    }

    private fun dirOf(track: TrackId): File = File(root, hash(track.key))

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(16).toByteArray().toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
