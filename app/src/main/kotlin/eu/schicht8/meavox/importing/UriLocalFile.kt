// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import eu.schicht8.meavox.core.port.LocalFile
import java.io.FileNotFoundException
import java.io.InputStream

/** A picked or shared content [uri] as a [LocalFile]. Read once during import. */
class UriLocalFile(private val resolver: ContentResolver, private val uri: Uri) : LocalFile {
    override val displayName: String? by lazy {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } ?: uri.lastPathSegment
    }

    override fun open(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open $uri")
}
