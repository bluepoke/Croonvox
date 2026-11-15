// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox

import android.content.Context
import androidx.work.WorkManager
import app.croonvox.core.library.Library
import app.croonvox.core.library.TrackFiles
import app.croonvox.core.library.TrackRepository
import app.croonvox.data.AppTrackFiles
import app.croonvox.data.CroonvoxDatabase
import app.croonvox.data.RoomTrackRepository
import java.io.File

/**
 * Manual dependency injection: the one place that wires adapters to the domain core.
 * Everything is created lazily on first use.
 */
class AppContainer(private val context: Context) {
    val database: CroonvoxDatabase by lazy { CroonvoxDatabase.create(context) }
    val trackRepository: TrackRepository by lazy { RoomTrackRepository(database) }
    val trackFiles: TrackFiles by lazy { AppTrackFiles(File(context.filesDir, "tracks")) }
    val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    val library: Library by lazy { Library(trackRepository) }
}
