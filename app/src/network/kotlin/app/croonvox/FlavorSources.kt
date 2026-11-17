// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox

import app.croonvox.core.port.Source
import app.croonvox.newpipe.NewPipeSources

/** The network flavor's Sources (ADR 1): searchable services behind NewPipeExtractor. */
internal object FlavorSources {
    fun create(): List<Source> = NewPipeSources.create()
}
