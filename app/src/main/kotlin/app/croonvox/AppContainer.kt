// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox

import android.content.Context
import androidx.work.WorkManager
import app.croonvox.core.library.ImportLocalFile
import app.croonvox.core.library.Library
import app.croonvox.core.library.TrackFiles
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.pipeline.SeparateTrack
import app.croonvox.core.pipeline.TrackPipeline
import app.croonvox.core.player.TrackPlayer
import app.croonvox.core.port.AudioEngine
import app.croonvox.core.port.Separator
import app.croonvox.core.session.Sessions
import app.croonvox.core.session.SettingsRepository
import app.croonvox.core.session.TakeRepository
import app.croonvox.data.AppTrackFiles
import app.croonvox.data.CroonvoxDatabase
import app.croonvox.data.RoomSettingsRepository
import app.croonvox.data.RoomTakeRepository
import app.croonvox.data.RoomTrackRepository
import app.croonvox.engine.OboeAudioEngine
import app.croonvox.importing.Importer
import app.croonvox.separating.EngineDecoder
import app.croonvox.separating.EngineFlac
import app.croonvox.separating.WorkManagerPipelineJobs
import app.croonvox.separation.ModelSeparator
import app.croonvox.separation.ModelStore
import app.croonvox.separation.SeparationCatalogue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Manual dependency injection: the one place that wires adapters to the domain core.
 * Everything is created lazily on first use and lives as long as the process.
 */
class AppContainer(private val context: Context) {
    /** Work that must outlive any screen (e.g. imports). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: CroonvoxDatabase by lazy { CroonvoxDatabase.create(context) }
    val trackRepository: TrackRepository by lazy { RoomTrackRepository(database) }
    val trackFiles: TrackFiles by lazy { AppTrackFiles(File(context.filesDir, "tracks")) }
    val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    val audioEngine: AudioEngine by lazy { OboeAudioEngine(context) }
    val takeRepository: TakeRepository by lazy { RoomTakeRepository(database) }
    val settingsRepository: SettingsRepository by lazy { RoomSettingsRepository(database) }

    val library: Library by lazy { Library(trackRepository) }
    val importLocalFile: ImportLocalFile by lazy { ImportLocalFile(trackRepository, trackFiles) }
    val trackPlayer: TrackPlayer by lazy { TrackPlayer(trackRepository, trackFiles, audioEngine) }
    val sessions: Sessions by lazy {
        Sessions(trackRepository, takeRepository, trackFiles, audioEngine, settingsRepository)
    }
    val importer: Importer by lazy { Importer(appScope, importLocalFile, trackFiles, context.contentResolver) }

    // Separation (ADR 3, ADR 15): models are downloaded into app storage on first use.
    val separator: Separator by lazy {
        ModelSeparator(SeparationCatalogue.models, ModelStore(File(context.filesDir, "models")), EngineDecoder, EngineFlac)
    }
    val separateTrack: SeparateTrack by lazy { SeparateTrack(trackRepository, trackFiles, separator) }
    val trackPipeline: TrackPipeline by lazy {
        TrackPipeline(trackRepository, WorkManagerPipelineJobs(workManager), SeparationCatalogue.default.id)
    }
}
