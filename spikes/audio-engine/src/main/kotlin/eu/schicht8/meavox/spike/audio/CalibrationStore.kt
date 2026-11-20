// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.audio

import android.content.Context

/** Latency Calibration results per output route kind and sample rate (frames). */
class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("latency_calibration", Context.MODE_PRIVATE)

    fun get(kind: RouteKind, sampleRate: Int): Long? =
        prefs.getLong(key(kind, sampleRate), -1L).takeIf { it >= 0 }

    fun put(kind: RouteKind, sampleRate: Int, frames: Long) {
        prefs.edit().putLong(key(kind, sampleRate), frames).apply()
    }

    fun clear(kind: RouteKind, sampleRate: Int) {
        prefs.edit().remove(key(kind, sampleRate)).apply()
    }

    private fun key(kind: RouteKind, sampleRate: Int) = "${kind.name}.$sampleRate"
}
