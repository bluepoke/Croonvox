// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.testing

import eu.schicht8.meavox.core.port.LocalFile
import java.io.ByteArrayInputStream
import java.io.InputStream

/** A picked/shared file with in-memory [content]; equal content means the same Track. */
class FakeLocalFile(
    override val displayName: String?,
    private val content: String,
) : LocalFile {
    override fun open(): InputStream = ByteArrayInputStream(content.toByteArray())
}
