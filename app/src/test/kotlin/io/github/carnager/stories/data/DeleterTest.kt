package io.github.carnager.stories.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeleterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(path: String, text: String = "x") = File(tmp.root, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun book(folder: String, vararg files: String) = Book(
        id = folder, folder = File(tmp.root, folder).path, title = folder, author = "", narrator = "", year = "", series = "",
        tracks = files.map { Track(File(tmp.root, it).path, it, 1000) }, cover = null, fingerprint = folder,
    )

    @Test
    fun `an episode goes with its folder, the series and the other episodes stay`() {
        file("Books/Series/E1/01.mp3"); file("Books/Series/E1/cover.jpg"); file("Books/Series/E1/position.sabp.dat")
        file("Books/Series/E2/01.mp3")
        val result = Deleter.delete(listOf(book("Books/Series/E1", "Books/Series/E1/01.mp3")), listOf(File(tmp.root, "Books").path))
        assertEquals(1, result.deleted)
        assertFalse(File(tmp.root, "Books/Series/E1").exists())
        assertTrue(File(tmp.root, "Books/Series/E2/01.mp3").exists())
    }

    @Test
    fun `disc folders and the book folder above them go`() {
        file("Books/Big/CD1/1.mp3"); file("Books/Big/CD2/1.mp3"); file("Books/Big/folder.jpg")
        Deleter.delete(listOf(book("Books/Big", "Books/Big/CD1/1.mp3", "Books/Big/CD2/1.mp3")), listOf(File(tmp.root, "Books").path))
        assertFalse(File(tmp.root, "Books/Big").exists())
        assertTrue(File(tmp.root, "Books").exists())
    }

    @Test
    fun `a folder with something else in it is kept`() {
        file("Books/Mine/1.mp3"); file("Books/Mine/notes.txt")
        Deleter.delete(listOf(book("Books/Mine", "Books/Mine/1.mp3")), listOf(File(tmp.root, "Books").path))
        assertFalse(File(tmp.root, "Books/Mine/1.mp3").exists())
        assertTrue(File(tmp.root, "Books/Mine/notes.txt").exists())
    }

    @Test
    fun `another book's audio keeps the shared folder and its cover`() {
        file("Books/Two/a.m4b"); file("Books/Two/b.m4b"); file("Books/Two/cover.jpg")
        Deleter.delete(listOf(book("Books/Two", "Books/Two/a.m4b")), listOf(File(tmp.root, "Books").path))
        assertTrue(File(tmp.root, "Books/Two/b.m4b").exists())
        assertTrue(File(tmp.root, "Books/Two/cover.jpg").exists())
    }

    @Test
    fun `the chosen folder itself stays, even emptied`() {
        file("Books/loose.m4b")
        Deleter.delete(listOf(book("Books", "Books/loose.m4b")), listOf(File(tmp.root, "Books").path))
        assertTrue(File(tmp.root, "Books").isDirectory)
    }
}
