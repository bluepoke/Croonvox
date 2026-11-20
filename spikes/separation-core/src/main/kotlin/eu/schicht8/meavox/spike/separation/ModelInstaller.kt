// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** Installs candidates into `<root>/<candidate id>/`: downloaded, or imported from a file the human converted. */
class ModelInstaller(private val root: File) {
    fun dirOf(candidate: Candidate) = File(root, candidate.id)

    fun isInstalled(candidate: Candidate) = candidate.isInstalled(dirOf(candidate))

    /** Downloads (and unpacks) [candidate]; [onProgress] receives bytes so far and the expected total. */
    fun download(candidate: Candidate, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean = { false }) {
        val download = requireNotNull(candidate.download) { "${candidate.name} has no download; convert and import it" }
        val dir = dirOf(candidate).apply { mkdirs() }
        val part = File(dir, "download.part")
        try {
            fetch(download.url, part, download.bytes, onProgress, isCancelled)
            if (download.archive) {
                extract(part, dir, candidate.files)
            } else {
                check(part.renameTo(File(dir, candidate.files.single()))) { "could not move the download into place" }
            }
            check(isInstalled(candidate)) { "download did not contain ${candidate.files}" }
        } finally {
            part.delete()
        }
    }

    /** Copies a model file (e.g. a converted `htdemucs.onnx`) into place as [file] of [candidate]. */
    fun import(candidate: Candidate, file: String, input: InputStream) {
        require(file in candidate.files) { "$file is not part of ${candidate.name}" }
        val dir = dirOf(candidate).apply { mkdirs() }
        val part = File(dir, "$file.part")
        input.use { source -> part.outputStream().use { source.copyTo(it, 1 shl 16) } }
        check(part.renameTo(File(dir, file))) { "could not move the import into place" }
    }

    fun delete(candidate: Candidate) {
        dirOf(candidate).deleteRecursively()
    }

    private fun fetch(url: String, into: File, expected: Long, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) {
        val connection = open(url)
        try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode} for $url" }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expected
            var done = 0L
            connection.inputStream.use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        if (isCancelled()) throw InterruptedException("download cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Opens [url], following redirects (GitHub release assets redirect to another host). */
    private fun open(url: String): HttpURLConnection {
        var current = URI(url)
        repeat(MAX_REDIRECTS) {
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            if (connection.responseCode !in 300..399) return connection
            val next = connection.getHeaderField("Location") ?: error("redirect without Location from $current")
            connection.disconnect()
            current = current.resolve(next)
        }
        error("too many redirects for $url")
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

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
