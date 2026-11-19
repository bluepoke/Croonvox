// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.port

/** Where Exports go: `Music/Croonvox/`, from where they can be shared (ADR 8). */
interface ExportStore {
    /**
     * Starts a new file named after [title] that will hold [frames] stereo frames at [sampleRate]
     * in [format]. It stays hidden from other apps until [ExportWriter.commit].
     */
    suspend fun create(title: String, format: ExportFormat, sampleRate: Int, frames: Long): ExportWriter
}

/** One Export being written: the rendered audio, then [commit] or [abort]. */
interface ExportWriter : PcmSink {
    /** Completes the file and makes it visible; returns where it is. */
    suspend fun commit(): ExportedFile

    /** Removes the unfinished file. */
    suspend fun abort()
}

/** A finished Export: a content URI other apps (e.g. the share sheet) can open, and its file name. */
data class ExportedFile(val uri: String, val displayName: String)

/** The encoding of an Export (ADR 8). MP3 and M4A/AAC come with #15. */
sealed interface ExportFormat {
    /** PCM WAV, 16-bit or 24-bit. */
    data class Wav(val bitsPerSample: Int = 16) : ExportFormat {
        init {
            require(bitsPerSample == 16 || bitsPerSample == 24) { "WAV is exported at 16 or 24 bits, not $bitsPerSample" }
        }
    }
}
