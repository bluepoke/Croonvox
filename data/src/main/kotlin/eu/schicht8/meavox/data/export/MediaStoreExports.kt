// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.data.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportStore
import eu.schicht8.meavox.core.port.ExportTags
import eu.schicht8.meavox.core.port.ExportWriter
import eu.schicht8.meavox.core.port.ExportedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * [ExportStore] in MediaStore's `Music/MeaVox/` (ADR 8). A file is pending, so other apps don't
 * see it, until it is complete. Needs no permission from Android 10 (the app's minimum).
 */
class MediaStoreExports(private val resolver: ContentResolver) : ExportStore {
    override suspend fun create(tags: ExportTags, format: ExportFormat, sampleRate: Int, frames: Long?): ExportWriter =
        // Not cancelled half-way: a row inserted but not handed back would stay pending behind the user.
        withContext(Dispatchers.IO + NonCancellable) {
            val wav = format as ExportFormat.Wav
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "${fileName(tags.title)}.wav")
                put(MediaStore.Audio.Media.TITLE, tags.title)
                tags.artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                put(MediaStore.Audio.Media.MIME_TYPE, WAV_MIME_TYPE)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/MeaVox")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw IOException("could not create an Export in Music/MeaVox")
            // "rw": a file of unknown length gets its header written over at the end.
            val descriptor = try {
                resolver.openFileDescriptor(uri, "rw") ?: throw IOException("could not open $uri")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            Writer(uri, descriptor, wav.bitsPerSample, sampleRate, frames, WavTags(tags.title, tags.artist))
        }

    private inner class Writer(
        private val uri: Uri,
        private val descriptor: ParcelFileDescriptor,
        bitsPerSample: Int,
        sampleRate: Int,
        frames: Long?,
        tags: WavTags,
    ) : ExportWriter {
        private val file = FileOutputStream(descriptor.fileDescriptor)
        private val stream = file.buffered(BUFFER_BYTES)
        private val encoder = WavEncoder(stream, sampleRate, channels = 2, bitsPerSample = bitsPerSample, frames = frames, tags = tags)
        private val patchHeader = frames == null

        override fun write(interleaved: FloatArray, frames: Int) = encoder.write(interleaved, frames)

        private fun close() {
            runCatching { stream.close() }
            runCatching { descriptor.close() }
        }

        override suspend fun commit(): ExportedFile = withContext(Dispatchers.IO) {
            encoder.finish()
            if (patchHeader) file.channel.write(ByteBuffer.wrap(encoder.header()), 0)
            close()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            // MediaStore may have renamed it, e.g. "Song (Mix 1) (1).wav" next to an earlier Export.
            val name = resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ExportedFile(uri.toString(), name ?: uri.lastPathSegment.orEmpty())
        }

        override suspend fun abort() {
            withContext(Dispatchers.IO) {
                close()
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
            title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(120).ifEmpty { "MeaVox" }
    }
}
