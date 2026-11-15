// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import app.croonvox.ui.library.LibraryRoute
import app.croonvox.ui.theme.CroonvoxTheme
import app.croonvox.ui.track.TrackDetailRoute

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as CroonvoxApp).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            CroonvoxTheme {
                val nav = rememberNavController()
                LaunchedEffect(Unit) {
                    container.importer.results.collect { result ->
                        when (result) {
                            // Opens the new Track, or the existing one for content imported before.
                            is Importer.Result.Imported -> nav.openTrack(result.id)
                            is Importer.Result.Failed ->
                                Toast.makeText(this@MainActivity, "Import failed: ${result.reason}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                NavHost(nav, startDestination = LIBRARY) {
                    composable(LIBRARY) {
                        LibraryRoute(container.library, container.importer, onOpen = { nav.openTrack(it) })
                    }
                    composable(
                        TRACK,
                        arguments = listOf(navArgument("source") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        val args = checkNotNull(entry.arguments)
                        val id = TrackId(SourceId(checkNotNull(args.getString("source"))), checkNotNull(args.getString("id")))
                        // ViewModels here are scoped to this back-stack entry and cleared on Back.
                        TrackDetailRoute(id, container.trackRepository, container.trackPlayer)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A file shared into Croonvox from another app (Share → Croonvox). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        // Reopening from recents replays the original intent; its URI grant may be gone.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        container.importer.import(uri)
    }

    /** Shows a Track's detail, replacing any detail screen already open (one Track at a time). */
    private fun NavHostController.openTrack(id: TrackId) {
        navigate("track/${Uri.encode(id.source.value)}/${Uri.encode(id.sourceTrackId)}") {
            popUpTo(LIBRARY)
        }
    }

    private companion object {
        const val LIBRARY = "library"
        const val TRACK = "track/{source}/{id}"
    }
}
