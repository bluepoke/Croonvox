// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.library

import app.croonvox.core.port.Source
import app.croonvox.core.port.SourceCandidate

/**
 * Finds the song behind a link shared into the app (e.g. from a browser) (ADR 1). It is then added
 * like a search result ([Library.add]), once the user confirmed the import sheet (ADR 19).
 */
class LookUpSharedLink(private val sources: List<Source>) {
    /** The item [url] points to, or null if no Source knows the link. */
    suspend operator fun invoke(url: String): SourceCandidate? = sources.firstNotNullOfOrNull { it.lookUp(url) }
}
