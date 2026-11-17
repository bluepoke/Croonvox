// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox

import app.croonvox.core.port.Source

/** The local flavor has no network Sources (ADR 1): only the Local File Source. */
internal object FlavorSources {
    fun create(): List<Source> = emptyList()
}
