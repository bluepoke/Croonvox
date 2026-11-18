// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.importing

import android.net.Uri
import app.croonvox.core.port.SourceCandidate

/** A song waiting for the user to confirm the import sheet (ADR 19): a picked or shared file, or a shared link. */
sealed interface PendingImport {
    data class File(val uri: Uri, val name: String) : PendingImport

    data class Link(val candidate: SourceCandidate) : PendingImport
}
