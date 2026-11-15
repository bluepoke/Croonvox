// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import app.croonvox.core.library.TrackFiles
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * [TrackFiles] under an app-private [root] (e.g. `filesDir/tracks`). Each Track gets one
 * directory named by a hash of its identity, so source IDs never need escaping.
 */
class AppTrackFiles(private val root: File) : TrackFiles {
    override fun originalAudio(track: TrackId): AudioFile = AudioFile(File(dirOf(track), "original").path)

    override suspend fun deleteAll(track: TrackId) {
        withContext(Dispatchers.IO) { dirOf(track).deleteRecursively() }
    }

    private fun dirOf(track: TrackId): File = File(root, key(track))

    private fun key(track: TrackId): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(track.key.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
