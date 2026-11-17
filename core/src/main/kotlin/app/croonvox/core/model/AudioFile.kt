// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.model

/** An audio file owned by the app, as an absolute path in the platform's file system. */
@JvmInline
value class AudioFile(val path: String)

/** A directory owned by the app, as an absolute path in the platform's file system. */
@JvmInline
value class AppDirectory(val path: String)
