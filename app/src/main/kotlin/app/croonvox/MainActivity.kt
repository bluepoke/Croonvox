// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.IntentCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.TrackId
import app.croonvox.importing.Importer
import app.croonvox.importing.LinkImporter
import app.croonvox.ui.library.LibraryRoute
import app.croonvox.ui.lyrics.LyricsSearchRoute
import app.croonvox.ui.search.SearchRoute
import app.croonvox.separation.SeparationCatalogue
import app.croonvox.ui.session.SessionRoute
import app.croonvox.ui.storage.StorageRoute
import app.croonvox.ui.theme.CroonvoxTheme

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as CroonvoxApp).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark (CroonvoxTheme): light system-bar icons whatever the system setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            CroonvoxTheme {
                val nav = rememberNavController()
                LaunchedEffect(Unit) {
                    container.linkImporter.results.collect { result ->
                        when (result) {
                            is LinkImporter.Result.Added -> nav.openTrack(result.id, shared = true)
                            LinkImporter.Result.Unsupported ->
                                Toast.makeText(this@MainActivity, "Croonvox can't add songs from this link", Toast.LENGTH_LONG).show()
                            is LinkImporter.Result.Failed ->
                                Toast.makeText(this@MainActivity, "Can't add the song: ${result.reason}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    container.importer.results.collect { result ->
                        when (result) {
                            // Opens the new Track, or the existing one for content imported before.
                            is Importer.Result.Imported -> nav.openTrack(result.id, shared = result.shared)
                            is Importer.Result.Failed ->
                                Toast.makeText(this@MainActivity, "Import failed: ${result.reason}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                NavHost(nav, startDestination = LIBRARY) {
                    composable(LIBRARY) {
                        LibraryRoute(
                            container.library, container.importer,
                            onOpen = { nav.openTrack(it) },
                            onStorage = { nav.navigate(STORAGE) },
                            onSearch = if (container.sources.isEmpty()) null else ({ nav.navigate(SEARCH) }),
                        )
                    }
                    composable(STORAGE) {
                        StorageRoute(container.trackStorage, SeparationCatalogue.models.associate { it.id to it.name })
                    }
                    composable(SEARCH) {
                        SearchRoute(container.sources, container.library, onAdded = { nav.openTrack(it) })
                    }
                    composable(
                        TRACK,
                        arguments = listOf(
                            navArgument("source") { type = NavType.StringType },
                            navArgument("id") { type = NavType.StringType },
                            navArgument("shared") {
                                type = NavType.BoolType
                                defaultValue = false
                            },
                        ),
                    ) { entry ->
                        val args = checkNotNull(entry.arguments)
                        val id = TrackId(SourceId(checkNotNull(args.getString("source"))), checkNotNull(args.getString("id")))
                        // ViewModels here are scoped to this back-stack entry and cleared on Back.
                        SessionRoute(
                            id, container.trackRepository, container.sessions, container.trackPipeline,
                            container.lyricsRepository, container.matchLyrics, container.lyricsOffset, container.vocalFreeToggle,
                            offerVocalFree = args.getBoolean("shared"),
                            onChooseLyrics = { nav.navigate(route(LYRICS, id)) },
                        )
                    }
                    composable(
                        LYRICS,
                        arguments = listOf(navArgument("source") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        val args = checkNotNull(entry.arguments)
                        val id = TrackId(SourceId(checkNotNull(args.getString("source"))), checkNotNull(args.getString("id")))
                        LyricsSearchRoute(id, container.matchLyrics, onChosen = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A file or link shared into Croonvox from another app (Share → Croonvox). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        // Reopening from recents replays the original intent; its URI grant may be gone.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (uri != null) return container.importer.import(uri, shared = true)
        // A link (network flavor only receives text): a video page shared from a browser or app.
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let(container.linkImporter::add)
    }

    /**
     * Shows a Track's screen with its Session, replacing any Track screen already open (one Track at a time).
     * [shared]: it was just added from a share, so the screen offers to mark it Vocal-Free (ADR 14).
     */
    private fun NavHostController.openTrack(id: TrackId, shared: Boolean = false) {
        navigate("track/${Uri.encode(id.source.value)}/${Uri.encode(id.sourceTrackId)}?shared=$shared") {
            popUpTo(LIBRARY)
        }
    }

    private companion object {
        const val LIBRARY = "library"
        const val SEARCH = "search"
        const val STORAGE = "storage"
        const val TRACK = "track/{source}/{id}?shared={shared}"
        const val LYRICS = "lyrics/{source}/{id}"

        /** [pattern] (e.g. [LYRICS]) filled in for [id]. */
        fun route(pattern: String, id: TrackId) =
            pattern.replace("{source}", Uri.encode(id.source.value)).replace("{id}", Uri.encode(id.sourceTrackId))
    }
}
