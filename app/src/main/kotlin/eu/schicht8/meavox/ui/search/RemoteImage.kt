// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.search

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/** A thumbnail from the web, cached in memory; a plain box while it loads or if it can't. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier) {
    val image by produceState(url?.let(cache::get), url) {
        if (url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                URI(url).toURL().openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }?.also { cache.put(url, it) }
    }
    val loaded = image
    if (loaded != null) {
        Image(loaded, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

private val cache = LruCache<String, ImageBitmap>(64)
