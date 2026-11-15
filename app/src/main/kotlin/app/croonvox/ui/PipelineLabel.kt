// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui

import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackPipelineState

/** User-facing name of a Track Pipeline state. */
fun TrackPipelineState.label(): String = when (this) {
    TrackPipelineState.Added -> "Added"
    TrackPipelineState.Downloaded -> "Downloaded"
}

/** Secondary line for a Track: artist (if known) and pipeline state. */
fun Track.subtitle(): String = listOfNotNull(artist, pipeline.label()).joinToString(" · ")
