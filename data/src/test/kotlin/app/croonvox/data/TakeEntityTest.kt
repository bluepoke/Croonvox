// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.data

import app.croonvox.core.model.OutputRoute
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.Transpose
import kotlin.test.Test
import kotlin.test.assertEquals

class TakeEntityTest {
    private fun take(transpose: Transpose) = Take(
        id = TakeId("t1"),
        track = TrackId(SourceId("youtube"), "abc"),
        name = "Take 1",
        startFrame = 1_440_000,
        frames = 480_000,
        sampleRate = 48_000,
        route = OutputRoute.Usb,
        reportedLatencyFrames = 4_800,
        transpose = transpose,
        recordedAt = 1_700_000_000_000,
    )

    @Test
    fun `a Take is stored and read back with its Transpose`() {
        for (semitones in listOf(-6, -1, 0, 2, 6)) {
            val take = take(Transpose(semitones))
            assertEquals(take, take.toEntity().toTake(), "$semitones")
        }
    }
}
