// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.importing

import app.croonvox.core.library.Library
import app.croonvox.core.library.LookUpSharedLink
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TrackId
import app.croonvox.core.port.SourceCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Adds the songs behind links shared into the app (network flavor), outliving any screen: the link
 * is looked up, the import sheet asks how to add it (ADR 19), then it is added like a search result.
 */
class LinkImporter(
    private val scope: CoroutineScope,
    private val lookUpSharedLink: LookUpSharedLink,
    private val tracks: TrackRepository,
    private val library: Library,
) {
    sealed interface Result {
        /** The song is new: the import sheet asks how to add it. */
        data class Found(val candidate: SourceCandidate) : Result

        /** The song was added now, or before (it opens unchanged). */
        data class Added(val id: TrackId) : Result

        /** No Source knows the link, or it holds no link at all. */
        data object Unsupported : Result

        data class Failed(val reason: String) : Result
    }

    // Buffered until collected: a share that starts the app arrives before the screen listens.
    private val _results = Channel<Result>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val results: Flow<Result> = _results.receiveAsFlow()

    /** Looks up [text] as shared, e.g. "Check this out https://youtu.be/…": the first link in it counts. */
    fun lookUp(text: String) {
        val url = link.find(text)?.value?.trimEnd(*TRAILING_PUNCTUATION) ?: return run { _results.trySend(Result.Unsupported) }
        report("the link can't be opened") {
            val candidate = lookUpSharedLink(url) ?: return@report Result.Unsupported
            // Added before: it opens as it is, without asking again.
            if (tracks.get(candidate.id) != null) Result.Added(candidate.id) else Result.Found(candidate)
        }
    }

    /** Adds [candidate] as the import sheet asked: marked [vocalFree] (ADR 14), or to be separated with [model] (ADR 19). */
    fun add(candidate: SourceCandidate, vocalFree: Boolean, model: SeparationModelId?) {
        report("the song can't be added") { Result.Added(library.add(candidate, vocalFree, model).id) }
    }

    private fun report(failure: String, block: suspend () -> Result) {
        scope.launch {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Failed(e.message ?: failure)
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
