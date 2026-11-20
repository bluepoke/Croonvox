// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.newpipe

import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.port.MediaUnavailable
import eu.schicht8.meavox.core.port.ResolvedMedia
import eu.schicht8.meavox.core.port.Source
import eu.schicht8.meavox.core.port.SourceCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

/**
 * One service behind NewPipeExtractor as a [Source] (ADR 1): its own [id] namespaces the Tracks
 * (`youtube:<video id>`). [contentFilter] restricts searches to songs or videos where the service
 * has such a filter.
 */
class NewPipeSource internal constructor(
    internal val service: StreamingService,
    override val id: SourceId,
    override val name: String,
    private val contentFilter: String?,
) : Source {
    override suspend fun search(query: String): List<SourceCandidate> = extracting {
        val handler = service.searchQHFactory.fromQuery(query, listOfNotNull(contentFilter), "")
        SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType !in live && it.contentAvailability !in unavailable }
            .map { Candidates.from(it, service, id) }
    }

    override suspend fun lookUp(url: String): SourceCandidate? = extracting {
        if (service.getLinkTypeByUrl(url) != StreamingService.LinkType.STREAM) return@extracting null
        Candidates.from(StreamInfo.getInfo(service, url), service, id)
    }

    override suspend fun resolve(sourceTrackId: String): ResolvedMedia = extracting {
        val info = StreamInfo.getInfo(service, service.streamLHFactory.getUrl(sourceTrackId))
        val choice = AudioStreamChoice.best(info.audioStreams)
            ?: throw MediaUnavailable("$name offers no audio that can be downloaded for this song", retryable = false)
        ResolvedMedia(audioUri = choice.stream.content, audioKey = choice.key, videoUri = null)
    }

    /** Runs extractor code off the main thread and turns its failures into [MediaUnavailable]. */
    private suspend fun <T> extracting(block: () -> T): T = try {
        runInterruptible(Dispatchers.IO) { block() }
    } catch (e: MediaUnavailable) {
        throw e
    } catch (e: ContentNotAvailableException) {
        throw MediaUnavailable(e.message ?: "$name says this song isn't available", retryable = false, e)
    } catch (e: ReCaptchaException) {
        throw MediaUnavailable("$name wants a CAPTCHA solved; try again later", retryable = true, e)
    } catch (e: IOException) {
        throw MediaUnavailable("$name can't be reached: ${e.message}", retryable = true, e)
    } catch (e: ExtractionException) {
        throw MediaUnavailable("$name changed and MeaVox can't read it any more (${e.message}); an update may fix it", retryable = false, e)
    }

    private companion object {
        val live = setOf(StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM)

        /** Paid (e.g. SoundCloud Go+), members-only or not yet published: can't be downloaded. */
        val unavailable = setOf(ContentAvailability.PAID, ContentAvailability.MEMBERSHIP, ContentAvailability.UPCOMING)
    }
}
