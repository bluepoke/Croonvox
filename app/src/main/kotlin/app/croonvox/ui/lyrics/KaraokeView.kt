// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.croonvox.core.lyrics.Karaoke
import app.croonvox.core.lyrics.LrcLine
import app.croonvox.core.port.Playback
import java.util.Locale
import kotlin.math.abs

/**
 * The Karaoke View (GLOSSARY): synced Lyrics that follow the Audio Engine position. The current
 * line is highlighted (bold, larger); the list keeps it near the top third. Word progress within
 * the line isn't drawn: on device, a colour running through the words misled more than it helped.
 */
@Composable
fun KaraokeView(
    lines: List<LrcLine>,
    playback: Playback,
    offsetMs: Long,
    onNudgeOffset: (Long) -> Unit,
    onResetOffset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = Karaoke.at(lines, playback.positionMs, offsetMs)
    val list = rememberLazyListState()
    val lead = with(LocalDensity.current) { 96.dp.roundToPx() }
    LaunchedEffect(position.line) {
        // Before the first line (e.g. after seeking back to the intro) the list goes to the top.
        list.glideTo(maxOf(position.line, 0), lead = if (position.line >= 0) lead else 0)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OffsetControls(offsetMs, onNudgeOffset, onResetOffset)
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 240.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                val current = index == position.line
                // Enlarged by drawing, not by a bigger font: a font change would change the line's
                // height and shove every line below it at once.
                val scale by animateFloatAsState(if (current) CURRENT_SCALE else 1f, tween(GLIDE_MS), label = "scale")
                val color by animateColorAsState(
                    if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(GLIDE_MS),
                    label = "color",
                )
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = color,
                    lineHeight = 32.sp,
                    modifier = Modifier
                        .fillMaxWidth(1 / CURRENT_SCALE) // room for the enlarged line
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                )
            }
        }
    }
}

/** Nudges the Lyrics Offset; "+" shows the Lyrics later. */
@Composable
private fun OffsetControls(offsetMs: Long, onNudge: (Long) -> Unit, onReset: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Offset ${formatOffset(offsetMs)}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        for (step in listOf(-1_000L, -100L, 100L, 1_000L)) {
            TextButton(onClick = { onNudge(step) }) { Text(formatOffset(step)) }
        }
        TextButton(onClick = onReset, enabled = offsetMs != 0L) { Text("Reset") }
    }
}

private fun formatOffset(ms: Long): String =
    (if (ms > 0) "+" else if (ms < 0) "−" else "±") + String.format(Locale.getDefault(), "%.1f s", abs(ms) / 1000.0)

/**
 * Scrolls [index] to [lead] px below the top in one slow, eased movement. `animateScrollToItem`
 * jumps when the line isn't laid out yet and moves too abruptly for reading along.
 */
private suspend fun LazyListState.glideTo(index: Int, lead: Int) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index, -lead) // far away (a seek): no point in gliding across the song
        return
    }
    animateScrollBy((item.offset - lead).toFloat(), tween(GLIDE_MS, easing = FastOutSlowInEasing))
}

private const val GLIDE_MS = 600
private const val CURRENT_SCALE = 1.15f
