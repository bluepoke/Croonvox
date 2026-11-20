// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.separation

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** The downloaded file doesn't match its pinned checksum; it was deleted. */
class ModelCorrupt(message: String) : IOException(message)

/**
 * Installs Separation Models on demand into `<root>/<model id>/` (ADR 15). An interrupted download
 * continues where it stopped; nothing is installed unless the whole file matches its SHA-256.
 */
class ModelStore(private val root: File, private val http: Http = UrlHttp) {
    /** One HTTP GET from byte [fromByte]; [status] 206 means the body starts there, 200 at 0. */
    class Response(val status: Int, val body: InputStream) : AutoCloseable {
        override fun close() = body.close()
    }

    fun interface Http {
        fun get(url: String, fromByte: Long): Response
    }

    /** [model]'s files are installed: using it needs no download. Reads the disk. */
    fun isInstalled(model: SeparationModel): Boolean {
        val download = model.download ?: return true
        return download.files.all { File(File(root, model.id.value), it).isFile }
    }

    /**
     * Returns the directory with [model]'s files, downloading them first if needed. Blocking;
     * interrupting the thread cancels it. [onDownload] receives the downloaded fraction, then
     * [onInstall] the fraction verified and unpacked (that takes a while on slow devices).
     * @throws IOException on network errors (worth retrying) or [ModelCorrupt].
     */
    fun install(model: SeparationModel, onDownload: (Float) -> Unit = {}, onInstall: (Float) -> Unit = {}): File {
        val dir = File(root, model.id.value)
        val download = model.download ?: return dir
        if (isInstalled(model)) return dir
        root.mkdirs()
        val part = File(root, "${model.id.value}.download")
        fetch(download, part, onDownload)
        // Verifying reads the archive once, unpacking once more: each is half of installing.
        val size = part.length().toFloat()
        val actual = sha256(part) { read -> onInstall(0.5f * read / size) }
        if (actual != download.sha256) {
            part.delete()
            throw ModelCorrupt("${model.name}: download has SHA-256 $actual, expected ${download.sha256}")
        }
        val unpacking = File(root, "${model.id.value}.unpacking")
        unpacking.deleteRecursively()
        unpacking.mkdirs()
        if (download.archive) {
            extract(part, unpacking, download.files) { read -> onInstall(0.5f + 0.5f * read / size) }
        } else {
            // A plain file (e.g. one `.onnx`): it is the model's only file, moved rather than copied.
            val file = File(unpacking, download.files.single())
            if (!part.renameTo(file)) throw IOException("could not move $part to $file")
        }
        val missing = download.files.filterNot { File(unpacking, it).isFile }
        if (missing.isNotEmpty()) throw ModelCorrupt("${model.name}: download lacks $missing")
        dir.deleteRecursively()
        if (!unpacking.renameTo(dir)) throw IOException("could not move $unpacking to $dir")
        part.delete()
        onInstall(1f)
        return dir
    }

    private fun fetch(download: ModelDownload, part: File, onProgress: (Float) -> Unit) {
        if (part.length() >= download.bytes) return
        http.get(download.url, part.length()).use { response ->
            val append = when (response.status) {
                206 -> true
                200 -> false
                else -> throw IOException("HTTP ${response.status} for ${download.url}")
            }
            var done = if (append) part.length() else 0L
            FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    if (Thread.interrupted()) throw InterruptedException("download cancelled")
                    val n = response.body.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    onProgress((done.toFloat() / download.bytes).coerceAtMost(1f))
                }
            }
        }
        if (part.length() < download.bytes) throw IOException("download ended early at ${part.length()} of ${download.bytes} bytes")
    }

    private fun sha256(file: File, onRead: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        CountingStream(file.inputStream(), onRead).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                if (Thread.interrupted()) throw InterruptedException("install cancelled")
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Unpacks the entries named in [wanted] (by file name, ignoring directories) from a `.tar.bz2`.
     * [onRead] receives how many bytes of the archive were read so far.
     */
    private fun extract(archive: File, dir: File, wanted: List<String>, onRead: (Long) -> Unit) {
        val compressed = BufferedInputStream(CountingStream(archive.inputStream(), onRead), 1 shl 16)
        TarArchiveInputStream(BZip2CompressorInputStream(compressed)).use { tar ->
            while (true) {
                if (Thread.interrupted()) throw InterruptedException("install cancelled")
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in wanted) continue
                File(dir, name).outputStream().use { tar.copyTo(it, 1 shl 16) }
            }
        }
    }

    /** Reports the bytes read through it. */
    private class CountingStream(private val input: InputStream, private val onRead: (Long) -> Unit) : InputStream() {
        private var count = 0L

        override fun read(): Int = input.read().also { if (it >= 0) onRead(++count) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also {
            if (it > 0) {
                count += it
                onRead(count)
            }
        }

        override fun close() = input.close()
    }

    /** [Http] over `HttpURLConnection`, following redirects (GitHub release assets redirect to another host). */
    object UrlHttp : Http {
        private const val MAX_REDIRECTS = 5

        override fun get(url: String, fromByte: Long): Response {
            var current = URI(url)
            repeat(MAX_REDIRECTS) {
                val connection = current.toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = false
                if (fromByte > 0) connection.setRequestProperty("Range", "bytes=$fromByte-")
                val status = connection.responseCode
                if (status !in 300..399) {
                    if (status >= 400) {
                        connection.disconnect()
                        return Response(status, InputStream.nullInputStream())
                    }
                    return Response(status, ClosingStream(connection))
                }
                val next = connection.getHeaderField("Location") ?: throw IOException("redirect without Location from $current")
                connection.disconnect()
                current = current.resolve(next)
            }
            throw IOException("too many redirects for $url")
        }

        private class ClosingStream(private val connection: HttpURLConnection) : InputStream() {
            private val body = connection.inputStream

            override fun read(): Int = body.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = body.read(b, off, len)

            override fun close() {
                body.close()
                connection.disconnect()
            }
        }
    }
}
