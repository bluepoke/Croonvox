// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.croonvox.core.port.ExportFormat
import app.croonvox.core.port.ExportStore
import app.croonvox.core.port.ExportWriter
import app.croonvox.core.port.ExportedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * [ExportStore] in MediaStore's `Music/Croonvox/` (ADR 8). A file is pending, so other apps don't
 * see it, until it is complete. Needs no permission from Android 10 (the app's minimum).
 */
class MediaStoreExports(private val resolver: ContentResolver) : ExportStore {
    override suspend fun create(title: String, format: ExportFormat, sampleRate: Int, frames: Long): ExportWriter =
        // Not cancelled half-way: a row inserted but not handed back would stay pending behind the user.
        withContext(Dispatchers.IO + NonCancellable) {
            val wav = format as ExportFormat.Wav
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "${fileName(title)}.wav")
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.MIME_TYPE, WAV_MIME_TYPE)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Croonvox")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw IOException("could not create an Export in Music/Croonvox")
            val stream = try {
                resolver.openOutputStream(uri, "w")?.buffered(BUFFER_BYTES) ?: throw IOException("could not open $uri")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            Writer(uri, stream, WavEncoder(stream, sampleRate, channels = 2, bitsPerSample = wav.bitsPerSample, frames = frames))
        }

    private inner class Writer(private val uri: Uri, private val stream: OutputStream, private val encoder: WavEncoder) : ExportWriter {
        override fun write(interleaved: FloatArray, frames: Int) = encoder.write(interleaved, frames)

        override suspend fun commit(): ExportedFile = withContext(Dispatchers.IO) {
            encoder.finish()
            stream.close()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            // MediaStore may have renamed it, e.g. "Song (Mix 1) (1).wav" next to an earlier Export.
            val name = resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ExportedFile(uri.toString(), name ?: uri.lastPathSegment.orEmpty())
        }

        override suspend fun abort() {
            withContext(Dispatchers.IO) {
                runCatching { stream.close() }
                resolver.delete(uri, null, null)
            }
        }
    }

    companion object {
        /** The type Android's MIME map gives `.wav`, so MediaStore keeps the file name as it is. */
        const val WAV_MIME_TYPE = "audio/x-wav"

        private const val BUFFER_BYTES = 256 * 1024

        /** [title] without characters file systems reject. */
        private fun fileName(title: String): String =
            title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(120).ifEmpty { "Croonvox" }
    }
}
