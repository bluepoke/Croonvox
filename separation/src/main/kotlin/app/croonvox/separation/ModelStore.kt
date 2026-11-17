// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation

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

    /**
     * Returns the directory with [model]'s files, downloading them first if needed. Blocking;
     * interrupting the thread cancels it. [onProgress] receives the downloaded fraction.
     * @throws IOException on network errors (worth retrying) or [ModelCorrupt].
     */
    fun install(model: SeparationModel, onProgress: (Float) -> Unit = {}): File {
        val dir = File(root, model.id.value)
        val download = model.download ?: return dir
        if (download.files.all { File(dir, it).isFile }) return dir
        root.mkdirs()
        val part = File(root, "${model.id.value}.download")
        fetch(download, part, onProgress)
        val actual = sha256(part)
        if (actual != download.sha256) {
            part.delete()
            throw ModelCorrupt("${model.name}: download has SHA-256 $actual, expected ${download.sha256}")
        }
        val unpacking = File(root, "${model.id.value}.unpacking")
        unpacking.deleteRecursively()
        unpacking.mkdirs()
        extract(part, unpacking, download.files)
        val missing = download.files.filterNot { File(unpacking, it).isFile }
        if (missing.isNotEmpty()) throw ModelCorrupt("${model.name}: download lacks $missing")
        dir.deleteRecursively()
        if (!unpacking.renameTo(dir)) throw IOException("could not move $unpacking to $dir")
        part.delete()
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Unpacks the entries named in [wanted] (by file name, ignoring directories) from a `.tar.bz2`. */
    private fun extract(archive: File, dir: File, wanted: List<String>) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in wanted) continue
                File(dir, name).outputStream().use { tar.copyTo(it, 1 shl 16) }
            }
        }
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
