// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.lyrics

/**
 * Where the singer is in synced Lyrics: [line] is the index of the current line (-1 before the
 * first), and [sungChars] how many characters of its text are sung, fractional in between.
 */
data class KaraokePosition(val line: Int, val sungChars: Float)

/**
 * Karaoke View timing (GLOSSARY, ADR 6): which line is current at a playback position, and how
 * far into it the singer is. Word progress is interpolated across the line's time span.
 */
object Karaoke {
    /**
     * [lines] in time order; [positionMs] on the Track's audio. A positive [offsetMs] (the Lyrics
     * Offset) shows the Lyrics later: a line timed at t is current from t + [offsetMs] on.
     */
    fun at(lines: List<LrcLine>, positionMs: Long, offsetMs: Long): KaraokePosition {
        val t = positionMs - offsetMs
        val index = lines.indexOfLast { it.timeMs <= t }
        if (index < 0) return KaraokePosition(-1, 0f)
        val line = lines[index]
        // Sung until the next line, but no slower than a singing pace: before an instrumental
        // break, or at the very end, the line would otherwise crawl across the whole gap.
        val longest = maxOf(MIN_LINE_MS, line.text.length * MAX_MS_PER_CHAR)
        val end = minOf(lines.getOrNull(index + 1)?.timeMs ?: Long.MAX_VALUE, line.timeMs + longest)
        return KaraokePosition(index, sungChars(anchors(line, end), t))
    }

    /**
     * Points where the line's progress is known: character position at a time. Without word
     * timing that is the line's start and end, otherwise every word start (enhanced LRC).
     */
    private fun anchors(line: LrcLine, end: Long): List<WordStart> = buildList {
        add(WordStart(0, line.timeMs))
        for (word in line.words) if (word.charIndex > 0 || word.timeMs > line.timeMs) add(word)
        if (last().charIndex < line.text.length) add(WordStart(line.text.length, maxOf(end, last().timeMs)))
    }

    /** Characters sung at [t]: linear between the anchors around it. */
    private fun sungChars(anchors: List<WordStart>, t: Long): Float {
        val next = anchors.indexOfFirst { it.timeMs > t }
        if (next < 0) return anchors.last().charIndex.toFloat()
        if (next == 0) return 0f
        val a = anchors[next - 1]
        val b = anchors[next]
        val fraction = (t - a.timeMs).toFloat() / (b.timeMs - a.timeMs)
        return a.charIndex + (b.charIndex - a.charIndex) * fraction
    }

    private const val MIN_LINE_MS = 2_000L
    private const val MAX_MS_PER_CHAR = 250L
}
