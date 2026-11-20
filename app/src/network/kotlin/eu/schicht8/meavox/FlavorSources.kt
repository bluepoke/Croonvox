// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox

import eu.schicht8.meavox.core.port.Source
import eu.schicht8.meavox.newpipe.NewPipeSources

/** The network flavor's Sources (ADR 1): searchable services behind NewPipeExtractor. */
internal object FlavorSources {
    fun create(): List<Source> = NewPipeSources.create()
}
