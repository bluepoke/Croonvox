// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import app.croonvox.core.library.StagedFile
import app.croonvox.core.library.TrackFiles
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.TrackId
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

    private fun dirOf(track: TrackId): File = File(root, key(track))

    private fun key(track: TrackId): String =
        MessageDigest.getInstance("SHA-256").digest(track.key.toByteArray()).take(16).toByteArray().toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
