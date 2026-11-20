// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui

/** `m:ss` for a position or duration in milliseconds. */
fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
