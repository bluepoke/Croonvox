// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.port.ExportFormat
import app.croonvox.core.port.ExportStore
import app.croonvox.core.port.ExportWriter
import app.croonvox.core.port.ExportedFile

/** Keeps Exports in memory: what was written to each, and whether it was committed or aborted. */
class FakeExportStore : ExportStore {
    class Written(val title: String, val format: ExportFormat, val sampleRate: Int, val frames: Long) {
        var framesWritten = 0L
        var committed = false
        var aborted = false
    }

    val exports = mutableListOf<Written>()

    override suspend fun create(title: String, format: ExportFormat, sampleRate: Int, frames: Long): ExportWriter {
        val written = Written(title, format, sampleRate, frames).also { exports += it }
        return object : ExportWriter {
            override fun write(interleaved: FloatArray, frames: Int) {
                check(!written.committed && !written.aborted) { "already finished" }
                written.framesWritten += frames
            }

            override suspend fun commit(): ExportedFile {
                written.committed = true
                return ExportedFile("content://exports/${exports.indexOf(written)}", "$title.wav")
            }

            override suspend fun abort() {
                written.aborted = true
            }
        }
    }
}
