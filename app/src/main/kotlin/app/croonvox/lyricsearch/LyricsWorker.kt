// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.lyricsearch

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.croonvox.CroonvoxApp
import app.croonvox.core.lyrics.FetchLyrics
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.TrackId

/** Searches one Track's Lyrics once there is a network; retries with backoff (ADR 6, ADR 10). */
class LyricsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as CroonvoxApp).container

    override suspend fun doWork(): Result {
        val id = TrackId(
            SourceId(inputData.getString(SOURCE) ?: return Result.failure()),
            inputData.getString(SOURCE_TRACK_ID) ?: return Result.failure(),
        )
        // After a few retries the search gives up as Failed, so the user can search by hand.
        return when (container.fetchLyrics(id, lastAttempt = runAttemptCount >= MAX_ATTEMPTS - 1)) {
            FetchLyrics.Outcome.Done -> Result.success()
            FetchLyrics.Outcome.RetryLater -> Result.retry()
        }
    }

    companion object {
        const val SOURCE = "source"
        const val SOURCE_TRACK_ID = "sourceTrackId"
        private const val MAX_ATTEMPTS = 5
    }
}
