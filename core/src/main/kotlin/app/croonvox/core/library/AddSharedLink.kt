// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.library

import app.croonvox.core.model.Track
import app.croonvox.core.port.Source

/** Adds the song behind a link shared into the app (e.g. from a browser), like a search result (ADR 1). */
class AddSharedLink(private val sources: List<Source>, private val library: Library) {
    /** The Track for [url] (existing or new), or null if no Source knows the link. */
    suspend operator fun invoke(url: String): Track? {
        for (source in sources) {
            val candidate = source.lookUp(url) ?: continue
            return library.add(candidate)
        }
        return null
    }
}
