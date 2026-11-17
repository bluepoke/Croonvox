// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.newpipe

import app.croonvox.core.model.SourceId
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

/**
 * The network Sources of the app (network flavor only, ADR 1): YouTube and Bandcamp. SoundCloud
 * is left out for now: it serves songs only over HLS, which the downloader doesn't handle yet.
 */
object NewPipeSources {
    @Volatile private var initialised = false

    /** The Sources, with NewPipe set up for [locale] (search language and region) on first use. */
    fun create(locale: Locale = Locale.getDefault()): List<NewPipeSource> {
        synchronized(this) {
            if (!initialised) {
                NewPipe.init(NewPipeHttpClient, Localization.fromLocale(locale), ContentCountry(locale.country.ifEmpty { "US" }))
                initialised = true
            }
        }
        return listOf(
            NewPipeSource(ServiceList.YouTube, SourceId("youtube"), "YouTube", contentFilter = "videos"),
            // Bandcamp has no search filters; its non-track results are dropped by NewPipeSource.
            NewPipeSource(ServiceList.Bandcamp, SourceId("bandcamp"), "Bandcamp", contentFilter = null),
        )
    }

    /** NewPipe's [Downloader] over `HttpURLConnection`. */
    private object NewPipeHttpClient : Downloader() {
        // Services serve the pages NewPipe parses to a desktop browser; NewPipe's own app does the same.
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        override fun execute(request: Request): Response {
            val connection = URI(request.url()).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.httpMethod()
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                for ((name, values) in request.headers()) {
                    connection.setRequestProperty(name, values.joinToString(", "))
                }
                request.dataToSend()?.let { body ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val body = stream?.use { it.readBytes().decodeToString() } ?: ""
                val headers = connection.headerFields.filterKeys { it != null }
                return Response(status, connection.responseMessage, headers, body, connection.url.toString())
            } finally {
                connection.disconnect()
            }
        }
    }
}
