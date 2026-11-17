// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.model

/**
 * Pitch-shift of the Backing Audio in whole semitones, tempo unchanged (GLOSSARY: Transpose,
 * ADR 5). Each Take is bound to the Transpose it was sung at.
 */
@JvmInline
value class Transpose(val semitones: Int) {
    init {
        require(semitones in MIN..MAX) { "Transpose $semitones is outside $MIN..$MAX" }
    }

    companion object {
        const val MIN = -6
        const val MAX = 6
        val None = Transpose(0)

        /** The Transpose nearest to [semitones] within the range. */
        fun clamped(semitones: Int) = Transpose(semitones.coerceIn(MIN, MAX))
    }
}
