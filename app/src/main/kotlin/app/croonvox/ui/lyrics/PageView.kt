// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croonvox.core.lyrics.TrackLyrics
import app.croonvox.core.lyrics.pageText
import app.croonvox.ui.theme.CroonvoxTheme

/**
 * The Page View (GLOSSARY): the Lyrics as whole, readable verses that the singer scrolls. Works
 * with plain and synced Lyrics alike.
 */
@Composable
fun PageView(lyrics: TrackLyrics?, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (lyrics) {
            null, TrackLyrics.Searching -> Text("Searching Lyrics…", color = muted)
            is TrackLyrics.NotFound, is TrackLyrics.Failed ->
                Text("No Lyrics for this Track yet. Search for them on the Track's page.", color = muted)
            is TrackLyrics.Suggested -> Page(lyrics.lyrics.pageText(), "Suggested: ${lyrics.lyrics.name()} (${lyrics.lyrics.kind()})")
            is TrackLyrics.Confirmed -> Page(lyrics.lyrics.pageText(), "${lyrics.lyrics.name()} (${lyrics.lyrics.kind()})")
        }
    }
}

@Composable
private fun Page(text: String, caption: String) {
    val stanzas = remember(text) { text.split(Regex("""\n\s*\n""")) }
    Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (stanza in stanzas) {
        Text(stanza.trim(), style = CroonvoxTheme.type.lyricPage, color = MaterialTheme.colorScheme.onSurface)
    }
}
