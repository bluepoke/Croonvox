// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox

import eu.schicht8.meavox.core.port.Source

/** The local flavor has no network Sources (ADR 1): only the Local File Source. */
internal object FlavorSources {
    fun create(): List<Source> = emptyList()
}
