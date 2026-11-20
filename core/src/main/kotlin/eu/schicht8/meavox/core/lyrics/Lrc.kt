// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.lyrics

import eu.schicht8.meavox.core.port.LyricsCandidate

/**
 * One line of line-synced Lyrics: its [text] starts at [timeMs] (studio timing, ADR 6). Enhanced
 * LRC also says when each word starts: [words], as positions in [text], in order. A last entry at
 * the end of the text marks when the line ends.
 */
data class LrcLine(val timeMs: Long, val text: String, val words: List<WordStart> = emptyList())

/** The word starting at [charIndex] of its line's text is sung from [timeMs]. */
data class WordStart(val charIndex: Int, val timeMs: Long)

/**
 * Parser for LRC, the line-synced Lyrics format. Tolerates the extensions providers use (ADR 6):
 * metadata tags (`[ar:…]`; `[offset:±ms]` is applied), several timestamps on one line (a repeated
 * chorus), and the word-level timing of enhanced LRC (`<mm:ss.xx>`). Broken tags are dropped.
 */
object Lrc {
    private val timestamp = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTiming = Regex("""<(\d+):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val brokenTag = Regex("""<[^<>]*>""")
    private val offsetTag = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** The timed lines of [lrc], in time order. Lines without a timestamp (metadata, junk) are skipped. */
    fun parse(lrc: String): List<LrcLine> {
        // A positive offset makes the Lyrics come earlier: every timestamp moves forward by it.
        val shift = -(lrc.lineSequence().firstNotNullOfOrNull { offsetTag.find(it.trim())?.groupValues?.get(1)?.toLong() } ?: 0L)
        fun shifted(ms: Long) = maxOf(0L, ms + shift)
        return lrc.lineSequence().flatMap { line ->
            val times = mutableListOf<Long>()
            var rest = line.trim()
            while (true) {
                val match = timestamp.matchAt(rest, 0) ?: break
                times += millis(match)
                rest = rest.substring(match.range.last + 1)
            }
            val (text, words) = stripWordTags(rest)
            // Word tags are timed for the first timestamp; a repeat of the line moves them along.
            times.map { time ->
                LrcLine(shifted(time), text, words.map { it.copy(timeMs = shifted(it.timeMs - times.first() + time)) })
            }
        }.sortedBy { it.timeMs }.toList()
    }

    /** The text without word tags and with single spaces, and where the tags were in it. */
    private fun stripWordTags(rest: String): Pair<String, List<WordStart>> {
        val text = StringBuilder()
        val words = mutableListOf<WordStart>()
        var at = 0
        for (tag in wordTiming.findAll(rest) + sequenceOf(null)) {
            val piece = brokenTag.replace(rest.substring(at, tag?.range?.first ?: rest.length), "")
            for (c in piece) {
                if (!c.isWhitespace()) text.append(c) else if (text.isNotEmpty() && text.last() != ' ') text.append(' ')
            }
            if (tag != null) {
                words += WordStart(text.length, millis(tag))
                at = tag.range.last + 1
            }
        }
        val trimmed = text.toString().trimEnd()
        return trimmed to words.map { it.copy(charIndex = minOf(it.charIndex, trimmed.length)) }
    }

    private fun millis(match: MatchResult): Long {
        val (min, sec, frac) = match.destructured
        val fracMs = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').toInt()
        return (min.toLong() * 60 + sec.toLong()) * 1000 + fracMs
    }
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
