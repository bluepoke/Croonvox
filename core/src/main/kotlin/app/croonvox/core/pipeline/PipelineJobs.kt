// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.pipeline

import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TrackId

/**
 * Runs Track Pipeline steps as resumable background jobs that survive process death (ADR 10).
 * Implemented with WorkManager; faked in tests.
 */
interface PipelineJobs {
    /**
     * Queues [SeparateTrack] for [track] with [model], after any job already queued or running
     * for the Track. Calling it again is harmless: a job finding the Track done does nothing.
     */
    fun separate(track: TrackId, model: SeparationModelId)
}
