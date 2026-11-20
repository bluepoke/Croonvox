// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.importing

import android.net.Uri
import eu.schicht8.meavox.core.port.SourceCandidate

/** A song waiting for the user to confirm the import sheet (ADR 19): a picked or shared file, or a shared link. */
sealed interface PendingImport {
    data class File(val uri: Uri, val name: String) : PendingImport

    data class Link(val candidate: SourceCandidate) : PendingImport
}
