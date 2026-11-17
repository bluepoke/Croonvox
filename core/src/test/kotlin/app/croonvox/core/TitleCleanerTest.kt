// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.lyrics.CleanTitle
import app.croonvox.core.lyrics.TitleCleaner
import kotlin.test.Test
import kotlin.test.assertEquals

class TitleCleanerTest {
    @Test
    fun `clutter is removed and Artist - Title is split when the artist is unknown`() {
        val examples = mapOf<String, CleanTitle>(
            "Michael Jackson - Beat It (Official Video)" to CleanTitle("Michael Jackson", "Beat It"),
            "Adele - Rolling in the Deep (Official Music Video)" to CleanTitle("Adele", "Rolling in the Deep"),
            "Keimzeit - Kling Klang [HD]" to CleanTitle("Keimzeit", "Kling Klang"),
            "Queen – Bohemian Rhapsody (Remastered 2011) [4K]" to CleanTitle("Queen", "Bohemian Rhapsody"),
            "a-ha - Take On Me (Official Lyric Video)" to CleanTitle("a-ha", "Take On Me"),
            "Guns N' Roses - November Rain | Official Audio" to CleanTitle("Guns N' Roses", "November Rain"),
            "Daft Punk - Get Lucky ft. Pharrell Williams (Audio)" to CleanTitle("Daft Punk", "Get Lucky"),
            "Toto - Africa (Lyrics) HD" to CleanTitle("Toto", "Africa"),
            "Nena_-_99_Luftballons" to CleanTitle("Nena", "99 Luftballons"),
            "\"Yesterday\"" to CleanTitle(null, "Yesterday"),
            "Bohemian Rhapsody Official Video 4K" to CleanTitle(null, "Bohemian Rhapsody"),
            "Toto - Africa - Official Video" to CleanTitle("Toto", "Africa"),
            "Toto - Africa (Official Video - HD)" to CleanTitle("Toto", "Africa"),
            "Shakira - Hips Don't Lie (Lyrics / Letra)" to CleanTitle("Shakira", "Hips Don't Lie"),
            "Rammstein - Sonne (Offizielles Musikvideo)" to CleanTitle("Rammstein", "Sonne"),
            "Luis Fonsi - Despacito (Video Oficial)" to CleanTitle("Luis Fonsi", "Despacito"),
            "Stromae - Alors on danse (Clip Officiel)" to CleanTitle("Stromae", "Alors on danse"),
            "BTS - Dynamite Official MV" to CleanTitle("BTS", "Dynamite"),
            "Drake - God's Plan (Prod. by Cardo)" to CleanTitle("Drake", "God's Plan"),
        )
        for ((raw, expected) in examples) assertEquals(expected, TitleCleaner.clean(raw, artist = null), raw)
    }

    @Test
    fun `a known artist in front of the title is stripped from it`() {
        assertEquals(CleanTitle("Michael Jackson", "Billie Jean"), TitleCleaner.clean("Michael Jackson - Billie Jean (Official Video)", artist = "Michael Jackson"))
        assertEquals(CleanTitle("Michael Jackson", "Thriller"), TitleCleaner.clean("Thriller (Official 4K Video)", artist = "Michael Jackson - Topic"))
    }

    @Test
    fun `an auto-generated artist channel's title is the song title, even with a dash in it`() {
        // YouTube's "Artist - Topic" channels name videos after the song alone.
        assertEquals(
            CleanTitle("Nirvana", "Smells Like Teen Spirit - Live"),
            TitleCleaner.clean("Smells Like Teen Spirit - Live", artist = "Nirvana - Topic"),
        )
    }

    @Test
    fun `song titles made of clutter words are kept`() {
        val examples = mapOf<String, CleanTitle>(
            "Madonna - Music" to CleanTitle("Madonna", "Music"),
            "M - Pop Music" to CleanTitle("M", "Pop Music"),
            "India.Arie - Video (Official Video)" to CleanTitle("India.Arie", "Video"),
            "The Buggles - Video Killed the Radio Star" to CleanTitle("The Buggles", "Video Killed the Radio Star"),
        )
        for ((raw, expected) in examples) assertEquals(expected, TitleCleaner.clean(raw, artist = null), raw)
    }
}
