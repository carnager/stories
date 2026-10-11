package io.github.carnager.stories.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CueTest {
    private val sheet = """
        REM GENRE Audiobook
        PERFORMER "Michael Ende"
        TITLE "Momo"
        FILE "Momo.wav" WAVE
          TRACK 01 AUDIO
            TITLE "Erstes Kapitel"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "Zweites Kapitel"
            INDEX 00 12:29:00
            INDEX 01 12:30:37
          TRACK 03 AUDIO
            INDEX 01 75:00:00
    """.trimIndent()

    @Test
    fun `tracks become marks at their first index`() {
        val marks = Cue.parse(sheet).getValue("Momo.wav")
        assertEquals(listOf("Erstes Kapitel", "Zweites Kapitel", "Track 3"), marks.map { it.title })
        assertEquals(listOf(0L, 750_493L, 4_500_000L), marks.map { it.startMs })
    }

    @Test
    fun `a sheet naming the wav finds the mp3`() {
        val matched = Cue.match(Cue.parse(sheet), listOf("/b/Momo/Momo.mp3", "/b/Momo/Other.mp3"))
        assertEquals(setOf("/b/Momo/Momo.mp3"), matched.keys)
    }

    @Test
    fun `windows code page sheets are read`() {
        val bytes = "TITLE \"Hörbuch\"".toByteArray(charset("windows-1252"))
        assertEquals("TITLE \"Hörbuch\"", Cue.decode(bytes))
    }

    @Test
    fun `vorbis timestamps`() {
        assertEquals(3_723_500L, ChapterReader.timestamp("01:02:03.500"))
        assertEquals(62_000L, ChapterReader.timestamp("01:02"))
    }
}
