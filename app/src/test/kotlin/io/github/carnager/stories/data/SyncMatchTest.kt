package io.github.carnager.stories.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SyncMatchTest {
    private fun local(relative: String, size: Long) = LocalFile("/phone/Books/$relative", size, relative)

    @Test
    fun `same path and size match`() {
        val server = ServerFile("Author/Book/01.mp3", 100)
        val m = FileMatch(listOf(server), listOf(local("Author/Book/01.mp3", 100)))
        assertEquals(server, m.server("/phone/Books/Author/Book/01.mp3"))
        assertEquals("/phone/Books/Author/Book/01.mp3", m.local(server))
    }

    @Test
    fun `a different size is a different file`() {
        val m = FileMatch(listOf(ServerFile("Author/Book/01.mp3", 100)), listOf(local("Author/Book/01.mp3", 99)))
        assertNull(m.server("/phone/Books/Author/Book/01.mp3"))
    }

    @Test
    fun `elsewhere on the phone, name and size find it`() {
        val server = ServerFile("Author/Book/01.mp3", 100)
        val m = FileMatch(listOf(server), listOf(local("Other/01.mp3", 100)))
        assertEquals(server, m.server("/phone/Books/Other/01.mp3"))
    }

    @Test
    fun `an ambiguous name and size match nothing`() {
        val two = listOf(ServerFile("A/01.mp3", 100), ServerFile("B/01.mp3", 100))
        assertNull(FileMatch(two, listOf(local("C/01.mp3", 100))).server("/phone/Books/C/01.mp3"))
        val m = FileMatch(listOf(ServerFile("A/01.mp3", 100)), listOf(local("C/01.mp3", 100), local("D/01.mp3", 100)))
        assertNull(m.server("/phone/Books/C/01.mp3"))
        assertNull(m.server("/phone/Books/D/01.mp3"))
    }

    @Test
    fun `an exact match is not taken again by name`() {
        val a = ServerFile("A/01.mp3", 100)
        val b = ServerFile("B/01.mp3", 100)
        val m = FileMatch(listOf(a, b), listOf(local("A/01.mp3", 100), local("C/01.mp3", 100)))
        assertEquals(a, m.server("/phone/Books/A/01.mp3"))
        assertEquals(b, m.server("/phone/Books/C/01.mp3"))
    }

    @Test
    fun `addresses are normalized`() {
        assertEquals("http://192.168.1.10:8743", ServerAddress.normalize(" 192.168.1.10/ "))
        assertEquals("http://nas.local:9000", ServerAddress.normalize("nas.local:9000"))
        assertEquals("https://books.example.org", ServerAddress.normalize("https://books.example.org/"))
        assertEquals("https://example.org/books", ServerAddress.normalize("https://example.org/books/"))
        assertNull(ServerAddress.normalize("ftp://example.org"))
        assertNull(ServerAddress.normalize("http://user:pw@example.org"))
        assertNull(ServerAddress.normalize(""))
    }

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `private addresses`() {
        listOf("10.0.0.1", "172.16.0.1", "192.168.1.10", "127.0.0.1", "169.254.1.1", "100.64.0.1", "100.127.255.1", "::1", "fe80::1", "fd12::1")
            .forEach { assertTrue(it, ServerAddress.isPrivate(ip(it))) }
        listOf("8.8.8.8", "100.63.0.1", "100.128.0.1", "172.32.0.1", "2001:4860:4860::8888")
            .forEach { assertFalse(it, ServerAddress.isPrivate(ip(it))) }
    }

    @Test
    fun `plain http only to private addresses`() {
        assertTrue(ServerAddress.allowed("http://192.168.1.10:8743"))
        assertFalse(ServerAddress.allowed("http://8.8.8.8:8743"))
        assertTrue(ServerAddress.allowed("https://8.8.8.8"))
    }
}
