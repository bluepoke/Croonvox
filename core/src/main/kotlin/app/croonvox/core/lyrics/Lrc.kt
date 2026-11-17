// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.lyrics

import app.croonvox.core.port.LyricsCandidate

/** One line of line-synced Lyrics: its [text] starts at [timeMs] (studio timing, ADR 6). */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * Parser for LRC, the line-synced Lyrics format. Tolerates the extensions providers use (ADR 6):
 * metadata tags (`[ar:…]`), several timestamps on one line (a repeated chorus), and the
 * word-level timing of enhanced LRC (`<mm:ss.xx>`), which is dropped for now.
 */
object Lrc {
    private val timestamp = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTiming = Regex("""<\d+:\d{1,2}(?:[.:]\d{1,3})?>""")

    /** The timed lines of [lrc], in time order. Lines without a timestamp (metadata, junk) are skipped. */
    fun parse(lrc: String): List<LrcLine> = lrc.lineSequence().flatMap { line ->
        val times = mutableListOf<Long>()
        var rest = line.trim()
        while (true) {
            val match = timestamp.matchAt(rest, 0) ?: break
            val (min, sec, frac) = match.destructured
            val fracMs = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').toInt()
            times += (min.toLong() * 60 + sec.toLong()) * 1000 + fracMs
            rest = rest.substring(match.range.last + 1)
        }
        val text = wordTiming.replace(rest, "").replace(Regex("""\s+"""), " ").trim()
        times.map { LrcLine(it, text) }
    }.sortedBy { it.timeMs }.toList()
}

/**
 * The Lyrics as text for the Page View: the plain Lyrics if there are any, otherwise the synced
 * lines without their timing. An empty timed line marks a break between stanzas.
 */
fun LyricsCandidate.pageText(): String {
    plain?.let { return it.trim() }
    val lines = Lrc.parse(synced.orEmpty()).map { it.text }
    return buildList<String> {
        for (line in lines) {
            if (line.isEmpty() && (isEmpty() || last().isEmpty())) continue // one break at a time, none at the start
            add(line)
        }
    }.joinToString("\n").trim()
}
