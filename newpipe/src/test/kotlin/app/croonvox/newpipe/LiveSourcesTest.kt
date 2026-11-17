// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.newpipe

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Opt-in smoke test against the real services, because extractors break when services change:
 * `./gradlew :newpipe:test -PliveSources`. Each Source must find a well-known song, resolve it to
 * downloadable audio, recognise a link to it, and serve its first bytes to a ranged request (how
 * downloads resume).
 */
class LiveSourcesTest {
    @TestFactory
    fun `every Source finds, resolves and serves a well-known song`(): List<DynamicTest> {
        assumeTrue(System.getProperty("liveSources") == "true", "set -PliveSources to run against the real services")
        return NewPipeSources.create(Locale.US).map { source ->
            DynamicTest.dynamicTest(source.name) {
                runTest(timeout = 2.minutes) {
                    val results = source.search("Adele Rolling in the Deep")
                    assertTrue(results.isNotEmpty(), "${source.name} found nothing")
                    println("${source.name}: ${results.size} results")
                    // Not every result can be downloaded (e.g. SoundCloud Go+ previews); the first few must include one.
                    val (song, media) = results.take(5).firstNotNullOf { candidate ->
                        runCatching { candidate to source.resolve(candidate.id.sourceTrackId) }
                            .onFailure { println("${source.name}: ${candidate.id.key} not downloadable: ${it.message}") }
                            .getOrNull()
                    }
                    println("${source.name}: ${song.id.key} \"${song.title}\" by ${song.artist}, ${song.durationMs} ms, audio ${media.audioKey}")

                    // A shared link to the same song finds it again (the share-a-link path).
                    val link = source.service.streamLHFactory.getUrl(song.id.sourceTrackId)
                    assertEquals(song.id, source.lookUp(link)?.id, "lookUp($link)")

                    val connection = URI(media.audioUri).toURL().openConnection() as HttpURLConnection
                    connection.setRequestProperty("Range", "bytes=0-1023")
                    try {
                        assertTrue(connection.responseCode in listOf(200, 206), "HTTP ${connection.responseCode}")
                        val bytes = connection.inputStream.use { it.readNBytes(1024) }
                        assertEquals(1024, bytes.size)
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        }
    }
}
