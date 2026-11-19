// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.mix

import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.TrackId
import app.croonvox.core.session.BackingAudio
import kotlinx.coroutines.flow.Flow

/**
 * Non-destructive combination of one Backing Audio, one Take and [settings] (GLOSSARY: Mix, ADR 7).
 * Only the Mix Settings, the name and the Backing Audio change; the Take never does. The Mix spans
 * what Review plays: from the Take's Start Position until the Take ends.
 */
data class Mix(
    val id: MixId,
    val track: TrackId,
    val take: TakeId,
    val name: String,
    /** The Instrumental (by Separation Model) or the Original Audio (ADR 7, ADR 14). */
    val backing: BackingAudio,
    val settings: MixSettings,
    /** Epoch milliseconds. */
    val createdAt: Long,
)

@JvmInline
value class MixId(val value: String)

/**
 * Levels and alignment of a Mix (GLOSSARY: Mix Settings). The Guide Vocal level and Vocal Effects
 * come with #16.
 */
data class MixSettings(
    /** Level of the Backing Audio in dB, [MIN_BACKING_DB]..0. */
    val backingDb: Float,
    /** Level of the Take in dB, [MIN_VOCAL_DB]..[MAX_VOCAL_DB]. */
    val vocalDb: Float = 0f,
    /** Vocal Nudge: the Take sounds this much later (negative: earlier), on top of Latency Calibration. */
    val vocalNudgeMs: Int = 0,
) {
    init {
        require(backingDb in MIN_BACKING_DB..0f) { "backing level $backingDb dB is outside $MIN_BACKING_DB..0" }
        require(vocalDb in MIN_VOCAL_DB..MAX_VOCAL_DB) { "vocal level $vocalDb dB is outside $MIN_VOCAL_DB..$MAX_VOCAL_DB" }
        require(vocalNudgeMs in -MAX_NUDGE_MS..MAX_NUDGE_MS) { "Vocal Nudge $vocalNudgeMs ms is outside ±$MAX_NUDGE_MS" }
    }

    companion object {
        const val MIN_BACKING_DB = -40f
        const val MIN_VOCAL_DB = -20f
        const val MAX_VOCAL_DB = 12f
        const val MAX_NUDGE_MS = 200

        /** Mix Settings with each value moved into its range. */
        fun clamped(backingDb: Float, vocalDb: Float, vocalNudgeMs: Int) = MixSettings(
            backingDb.coerceIn(MIN_BACKING_DB, 0f),
            vocalDb.coerceIn(MIN_VOCAL_DB, MAX_VOCAL_DB),
            vocalNudgeMs.coerceIn(-MAX_NUDGE_MS, MAX_NUDGE_MS),
        )
    }
}

/**
 * Frames the Take is moved earlier against the timeline when it plays in a Mix: its sample i sounds at
 * Start Position + i − alignment (spec #1). That is the Latency Calibration offset minus the Vocal
 * Nudge; until Latency Calibration (#9), the offset is the engine's estimate from recording.
 */
fun Take.alignmentFrames(settings: MixSettings): Long = reportedLatencyFrames - settings.vocalNudgeMs.toLong() * sampleRate / 1000

/** Persistence of Mixes. Implemented by the data layer (Room); faked in tests. */
interface MixRepository {
    /** The Track's Mixes, oldest first, updated as they change. */
    fun observe(track: TrackId): Flow<List<Mix>>

    suspend fun get(id: MixId): Mix?

    suspend fun add(mix: Mix)

    /** Replaces the stored Mix with the same [Mix.id]; does nothing if there is none. */
    suspend fun update(mix: Mix)

    suspend fun delete(id: MixId)

    /** Deletes the Mixes of one Take. */
    suspend fun deleteForTake(take: TakeId)

    /** Deletes all of the Track's Mixes. */
    suspend fun deleteAll(track: TrackId)
}
