// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.pipeline

import app.croonvox.core.library.TrackFiles
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.PipelineStep
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.port.SeparationFailed
import app.croonvox.core.port.SeparationProgress
import app.croonvox.core.port.Separator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.floor

/** The Separating step of the Track Pipeline (ADR 10, ADR 15). */
class SeparateTrack(
    private val tracks: TrackRepository,
    private val files: TrackFiles,
    private val separator: Separator,
) {
    enum class Outcome {
        /** The stems are stored and the Track is Ready. */
        Ready,

        /** The Track is Failed; running the step again won't help until the user acts. */
        Failed,

        /** The Track stays Separating; run the step again later, it continues from its checkpoint. */
        RetryLater,

        /** The Track doesn't wait for this Separation (any more); nothing was done. */
        Skipped,
    }

    /**
     * Separates the Track with [model] if it is waiting for that: Downloaded, or Separating with
     * [model]. A job that runs after the Track moved on (e.g. one queued behind a job that
     * already finished it) changes nothing.
     */
    suspend operator fun invoke(id: TrackId, model: SeparationModelId): Outcome = coroutineScope {
        when (val state = tracks.get(id)?.pipeline) {
            TrackPipelineState.Ready(model) -> return@coroutineScope Outcome.Ready
            is TrackPipelineState.Failed -> return@coroutineScope Outcome.Failed
            TrackPipelineState.Downloaded -> Unit
            is TrackPipelineState.Separating -> if (state.model != model) return@coroutineScope Outcome.Skipped
            else -> return@coroutineScope Outcome.Skipped
        }
        tracks.updatePipeline(id, TrackPipelineState.Separating(model))
        // The Separator reports from any thread, sometimes many times a second (e.g. per block of a
        // download). Only the latest progress in whole percent is stored: the flow drops repeats,
        // so each stage writes the Track (and updates the notification) at most about 100 times.
        val progress = MutableStateFlow<SeparationProgress?>(null)
        val storing = launch {
            progress.filterNotNull().collect { tracks.updatePipeline(id, TrackPipelineState.Separating(model, it)) }
        }
        val failure = try {
            separator.separate(
                originalAudio = files.originalAudio(id),
                model = model,
                into = files.stems(id, model),
                checkpoint = files.separationCheckpoint(id, model),
                onProgress = { progress.value = it.inWholePercent() },
            )
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Errors too: a Separation that runs out of memory must end Failed, not stay
            // Separating and be queued again on every start.
            e
        } finally {
            storing.cancelAndJoin()
        }
        when {
            failure == null -> {
                tracks.updatePipeline(id, TrackPipelineState.Ready(model))
                Outcome.Ready
            }
            failure is SeparationFailed && failure.retryable -> {
                // Keep showing how far it got; the next run continues from the checkpoint.
                progress.value?.let { tracks.updatePipeline(id, TrackPipelineState.Separating(model, it)) }
                Outcome.RetryLater
            }
            else -> {
                val reason = when (failure) {
                    is OutOfMemoryError -> "out of memory"
                    else -> failure.message ?: failure::class.simpleName ?: "unknown error"
                }
                tracks.updatePipeline(id, TrackPipelineState.Failed(PipelineStep.Separating, reason))
                Outcome.Failed
            }
        }
    }
}

private fun SeparationProgress.inWholePercent() = copy(fraction = floor(fraction * 100) / 100)
