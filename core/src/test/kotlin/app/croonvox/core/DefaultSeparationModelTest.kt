// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.pipeline.DefaultSeparationModel
import app.croonvox.core.testing.InMemorySettingsRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultSeparationModelTest {
    private val settings = InMemorySettingsRepository()
    private val fast = SeparationModelId("fast")
    private val medium = SeparationModelId("medium")
    private val best = SeparationModelId("best")
    private val catalogue = listOf(fast, medium, best)

    @Test
    fun `the Default Separation Model starts at Fast`() = runTest {
        assertEquals(fast, DefaultSeparationModel(settings, catalogue, fallback = fast).get())
    }

    @Test
    fun `a chosen Default Separation Model is kept`() = runTest {
        DefaultSeparationModel(settings, catalogue, fallback = fast).set(best)

        // As after a restart: a new instance on the same stored settings.
        assertEquals(best, DefaultSeparationModel(settings, catalogue, fallback = fast).get())
    }

    @Test
    fun `a Default Separation Model that left the catalogue falls back to Fast`() = runTest {
        DefaultSeparationModel(settings, catalogue, fallback = fast).set(medium)

        assertEquals(fast, DefaultSeparationModel(settings, listOf(fast, best), fallback = fast).get())
    }
}
