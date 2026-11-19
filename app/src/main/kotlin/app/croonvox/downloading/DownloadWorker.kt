// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.downloading

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.croonvox.CroonvoxApp
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.pipeline.DownloadTrack
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Runs the Downloading step of one Track as a foreground job with a progress notification
 * (ADR 10). Process death or a stop by the system re-runs it, continuing after the bytes it has;
 * DownloadTrack decides when failed attempts are enough to give up.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as CroonvoxApp).container

    override suspend fun doWork(): Result = coroutineScope {
        val id = TrackId(
            SourceId(inputData.getString(SOURCE) ?: return@coroutineScope Result.failure()),
            inputData.getString(SOURCE_TRACK_ID) ?: return@coroutineScope Result.failure(),
        )
        val track = container.trackRepository.get(id) ?: return@coroutineScope Result.success()
        val waiting = track.pipeline == TrackPipelineState.Added || track.pipeline is TrackPipelineState.Downloading
        val notifying = if (waiting) {
            showProgress(track)
            launch {
                container.trackRepository.observe(id).filterNotNull().distinctUntilChanged { a, b -> a.pipeline == b.pipeline && a.title == b.title }
                    .collect { showProgress(it) }
            }
        } else {
            null
        }
        val outcome = container.downloadTrack(id)
        notifying?.cancel()
        when (outcome) {
            DownloadTrack.Outcome.Done -> Result.success()
            DownloadTrack.Outcome.RetryLater -> Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private suspend fun showProgress(track: Track) {
        try {
            setForeground(foregroundInfo(track))
        } catch (e: IllegalStateException) {
            // Android 12+ may refuse a foreground service for a job started in the background; it
            // then runs without one and continues later if the system stops it.
        }
    }

    private fun foregroundInfo(track: Track?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val progress = (track?.pipeline as? TrackPipelineState.Downloading)?.progress
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(track?.title ?: "Downloading")
            .setContentText(if (progress == null) "Waiting to download…" else "Downloading… ${(progress * 100).toInt()} %")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val SOURCE = "source"
        const val SOURCE_TRACK_ID = "sourceTrackId"
        private const val CHANNEL = "downloads"
    }
}
