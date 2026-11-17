// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TrackId
import app.croonvox.core.pipeline.PipelineJobs

/** Records the jobs queued, in order, without running them. */
class FakePipelineJobs : PipelineJobs {
    data class Separation(val track: TrackId, val model: SeparationModelId)

    val separations = mutableListOf<Separation>()

    override fun separate(track: TrackId, model: SeparationModelId) {
        separations += Separation(track, model)
    }
}
