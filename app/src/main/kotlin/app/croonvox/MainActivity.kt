// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.croonvox.ui.library.LibraryRoute
import app.croonvox.ui.theme.CroonvoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CroonvoxApp).container
        setContent {
            CroonvoxTheme {
                LibraryRoute(container.library)
            }
        }
    }
}
