// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.importing

import app.croonvox.core.library.AddSharedLink
import app.croonvox.core.model.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Adds the songs behind links shared into the app (network flavor), outliving any screen. */
class LinkImporter(private val scope: CoroutineScope, private val addSharedLink: AddSharedLink) {
    sealed interface Result {
        data class Added(val id: TrackId) : Result

        /** No Source knows the link, or it holds no link at all. */
        data object Unsupported : Result

        data class Failed(val reason: String) : Result
    }

    // Buffered until collected: a share that starts the app arrives before the screen listens.
    private val _results = Channel<Result>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val results: Flow<Result> = _results.receiveAsFlow()

    /** [text] as shared, e.g. "Check this out https://youtu.be/…": the first link in it counts. */
    fun add(text: String) {
        val url = link.find(text)?.value?.trimEnd(*TRAILING_PUNCTUATION) ?: return run { _results.trySend(Result.Unsupported) }
        scope.launch {
            val result = try {
                addSharedLink(url)?.let { Result.Added(it.id) } ?: Result.Unsupported
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Failed(e.message ?: "the link can't be opened")
            }
            _results.send(result)
        }
    }

    private companion object {
        val link = Regex("""https?://\S+""")

        /** Punctuation that ends a sentence around a link rather than the link itself. */
        val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '"', '\'', '»', '“', '”')
    }
}
