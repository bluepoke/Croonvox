// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.lyrics.pageText
import app.croonvox.core.port.LyricsCandidate
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsTextTest {
    private fun lyrics(plain: String?, synced: String?) = LyricsCandidate("1", "Adele", "Rolling in the Deep", null, plain, synced)

    @Test
    fun `plain Lyrics are shown as they are`() {
        val plain = "There's a fire starting in my heart\nReaching a fever pitch\n\nSee how I'll leave"

        assertEquals(plain, lyrics(plain, synced = "[00:01.00]ignored").pageText())
    }

    @Test
    fun `synced-only Lyrics lose their timing, in time order, with stanza breaks kept`() {
        val lrc = """
            [ar:Adele]
            [ti:Rolling in the Deep]
            [offset:+200]
            [00:01.00]There's a fire starting in my heart
            [00:05.50]<00:05.50>Reaching <00:06.00>a fever pitch
            [00:10.00]
            [00:11.00]
            [00:12.00][00:30.00]See how I'll leave
            [00:20.00] Second verse
        """.trimIndent()

        assertEquals(
            "There's a fire starting in my heart\nReaching a fever pitch\n\nSee how I'll leave\nSecond verse\nSee how I'll leave",
            lyrics(plain = null, synced = lrc).pageText(),
        )
    }
}
