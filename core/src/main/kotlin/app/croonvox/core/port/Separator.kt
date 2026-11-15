// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.port

import app.croonvox.core.model.AudioFile

/** On-device Separation of Original Audio into Instrumental and Vocal Stem (ADR 2, ADR 3). */
interface Separator {
    /**
     * Separates [originalAudio] with [model] and writes the result to [into]. The stems are
     * sample-aligned with, and as long as, the input. [onProgress] receives 0..1.
     */
    suspend fun separate(
        originalAudio: AudioFile,
        model: SeparationModelId,
        into: Stems,
        onProgress: (Float) -> Unit = {},
    )
}

/** Identifies one Separation Model of the catalogue (ADR 3). */
@JvmInline
value class SeparationModelId(val value: String)

/** Where the Instrumental and Vocal Stem of one Separation are stored. */
data class Stems(val instrumental: AudioFile, val vocalStem: AudioFile)
