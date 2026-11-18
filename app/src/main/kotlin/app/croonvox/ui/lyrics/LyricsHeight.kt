// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How many Lyrics lines the Karaoke View and the Page View show at once; the rest scrolls. */
internal const val VISIBLE_LYRICS_LINES = 5

/** The height of [VISIBLE_LYRICS_LINES] lines in [style], [spacing] apart (lines that wrap take more). */
@Composable
internal fun visibleLyricsHeight(style: TextStyle, spacing: Dp = 0.dp): Dp =
    with(LocalDensity.current) { style.lineHeight.toDp() } * VISIBLE_LYRICS_LINES + spacing * (VISIBLE_LYRICS_LINES - 1)
