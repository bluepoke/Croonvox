// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.port

/** A web source of Lyrics, e.g. LRCLIB (ADR 6). */
interface LyricsProvider {
    suspend fun search(query: LyricsQuery): List<LyricsCandidate>
}

data class LyricsQuery(val artist: String?, val title: String, val durationMs: Long?)

/**
 * One Lyrics match as delivered by the provider. [plain] is untimed text; [synced] is raw LRC
 * (line-synced, possibly enhanced). Either may be missing, not both.
 */
data class LyricsCandidate(
    val providerItemId: String,
    val artist: String?,
    val title: String,
    val durationMs: Long?,
    val plain: String?,
    val synced: String?,
) {
    init {
        require(plain != null || synced != null) { "a LyricsCandidate needs plain or synced Lyrics" }
    }
}
