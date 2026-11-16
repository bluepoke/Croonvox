// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation

import java.util.Locale

/** One benchmark run as the screen reports it: the measurement, or why it failed. */
data class RunRecord(
    val candidate: Candidate,
    val options: BenchmarkOptions,
    val result: BenchmarkResult?,
    val error: String?,
    /** `PowerManager.currentThermalStatus` before and after (0 = none … 6 = shutdown). */
    val thermalBefore: Int,
    val thermalAfter: Int,
)

/** Formats runs as the findings table in `docs/spikes/separation-spike.md`. */
object FindingsMarkdown {
    const val HEADER =
        "| Candidate | Tier | EP / threads / arena | Song | Load (ms) | Separation (s) | RTF | RTF total | " +
            "Segment mean / max (ms) | Peak RSS (MB, +Δ) | Frames in / instr. / vocal | Lag instr. / vocal | Thermal | Notes |\n" +
            "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"

    fun device(device: String, song: String, songSeconds: Double, songRate: Int, decodeMs: Long) =
        "**Device:** $device  \n**Song:** $song (%.0f s, %d Hz, decoded in %d ms)".format(Locale.US, songSeconds, songRate, decodeMs)

    fun row(run: RunRecord, song: String): String {
        val o = run.options
        val ep = "${o.runtime.provider} / ${o.runtime.threads} / ${if (o.runtime.cpuArena) "on" else "off"}"
        val r = run.result ?: return "| ${run.candidate.name} | ${run.candidate.tier} | $ep | $song | | | | | | | | | " +
            "${run.thermalBefore}→${run.thermalAfter} | failed: ${run.error?.replace('|', '/')} |"
        return String.format(
            Locale.US,
            "| %s | %s | %s | %s | %d | %.1f | %.2f | %.2f | %.0f / %d | %d (+%d) | %d / %d / %d | %s / %s | %d→%d | |",
            run.candidate.name, run.candidate.tier, ep, song, r.loadMs, r.separationMs / 1000.0, r.realTimeFactor,
            r.realTimeFactorTotal, r.segmentMsMean, r.segmentMsMax, r.peakRssBytes shr 20, r.peakDeltaBytes shr 20,
            r.inputFrames, r.instrumentalFrames, r.vocalStemFrames, r.instrumentalLag ?: "n/a", r.vocalStemLag ?: "n/a",
            run.thermalBefore, run.thermalAfter,
        )
    }
}
