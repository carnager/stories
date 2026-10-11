package io.github.carnager.stories.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream

class SabpReaderTest {
    @Test
    fun `reads a position file written by Smart AudioBook Player`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("position.sabp.dat")
        val p = SabpReader.parse(DataInputStream(stream))!!
        assertEquals("Sample - A Book For Testing (2000).mp3", p.fileName)
        assertEquals(3844, p.fileSeconds)
        assertEquals(3844, p.bookSeconds)
        assertEquals(4041, p.totalSeconds)
        assertEquals("Started", p.state)
        assertEquals(1f, p.speed)
        assertEquals(1700000000000L, p.lastPlayed)
    }

    @Test
    fun `reads a finished book, whose place SABP has set back to the start`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("finished.sabp.dat")
        val p = SabpReader.parse(DataInputStream(stream))!!
        assertEquals("Finished", p.state)
        assertEquals("Sample - A Book For Testing (2000).mp3", p.fileName)
        assertEquals(0, p.fileSeconds)
        assertEquals(4041, p.totalSeconds)
        assertEquals(3f, p.speed)
    }
}
