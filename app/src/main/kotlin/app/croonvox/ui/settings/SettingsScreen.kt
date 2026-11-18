// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.croonvox.core.model.SeparationModelId
import app.croonvox.separating.ModelChoices
import app.croonvox.separating.ModelOption
import app.croonvox.ui.models.ModelPicker
import kotlinx.coroutines.launch

/** Settings: the Default Separation Model (ADR 19). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(choices: ModelChoices) {
    var options by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var default by remember { mutableStateOf<SeparationModelId?>(null) }
    LaunchedEffect(choices) {
        options = choices.options()
        default = choices.default.get()
    }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Default vocal removal", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pre-selected when you add a song or re-separate one; you can pick another there. " +
                    "Changing it doesn't touch songs already added. A model is downloaded the first time it is used.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelPicker(options, default, onSelect = { model ->
                default = model
                scope.launch { choices.default.set(model) }
            }, durationMs = null)
        }
    }
}
