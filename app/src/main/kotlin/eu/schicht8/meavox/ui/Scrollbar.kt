// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a thin scrollbar thumb at the right edge while [state]'s content overflows. Compose has
 * no scrollbar of its own on Android. Put it before `verticalScroll(state)` in the chain, so it
 * measures the viewport, not the content.
 */
fun Modifier.verticalScrollbar(state: ScrollState, color: Color, width: Dp = 4.dp, inset: Dp = 3.dp): Modifier =
    drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val viewport = size.height
        val thumb = (viewport * viewport / (viewport + max)).coerceAtLeast(32.dp.toPx())
        val top = (viewport - thumb) * state.value / max
        val w = width.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - w - inset.toPx(), top),
            size = Size(w, thumb),
            cornerRadius = CornerRadius(w / 2),
        )
    }
