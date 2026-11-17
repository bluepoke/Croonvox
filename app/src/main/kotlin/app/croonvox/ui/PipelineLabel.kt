// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui

import app.croonvox.core.model.PipelineStep
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.port.SeparationProgress

/** User-facing name of a Track Pipeline state. */
fun TrackPipelineState.label(): String = when (this) {
    TrackPipelineState.Added -> "Added"
    is TrackPipelineState.Downloading -> progress.let { p ->
        when {
            retryReason != null -> "Download paused, retrying soon"
            p != null -> "Downloading ${percent(p)}"
            else -> "Waiting to download"
        }
    }
    TrackPipelineState.Downloaded -> "Downloaded"
    is TrackPipelineState.Separating -> progress.let { p ->
        when (p?.stage) {
            null -> "Waiting to remove vocals"
            SeparationProgress.Stage.DownloadingModel -> "Downloading model ${percent(p.fraction)}"
            SeparationProgress.Stage.InstallingModel -> "Installing model ${percent(p.fraction)}"
            SeparationProgress.Stage.Separating -> "Removing vocals ${percent(p.fraction)}"
        }
    }
    is TrackPipelineState.Ready -> "Ready"
    is TrackPipelineState.StemsDeleted -> "Stems deleted"
    is TrackPipelineState.Failed -> when (step) {
        PipelineStep.Downloading -> "Download failed"
        PipelineStep.Separating -> "Vocal removal failed"
    }
}

private fun percent(fraction: Float) = "${(fraction * 100).toInt()} %"

/** Secondary line for a Track: artist (if known), pipeline state, and whether it is Vocal-Free. */
fun Track.subtitle(): String = listOfNotNull(artist, pipeline.label(), "Vocal-Free".takeIf { vocalFree }).joinToString(" · ")
