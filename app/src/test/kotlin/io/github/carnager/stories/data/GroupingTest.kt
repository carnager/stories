package io.github.carnager.stories.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupingTest {
    private fun f(path: String, album: String = "", track: Int = 0, disc: Int = 0, ms: Long = 1000) =
        FileTags(path, size = path.length.toLong(), modified = 0, album = album, track = track, disc = disc, durationMs = ms)

    @Test
    fun `each episode folder of a series is its own book`() {
        val files = listOf(
            f("/A/Series/Folge 1/01.mp3"), f("/A/Series/Folge 1/02.mp3"),
            f("/A/Series/Folge 2/01.mp3"), f("/A/Series/Folge 10/01.mp3"),
        )
        val drafts = Grouping.group(files).sortedWith { a, b -> NaturalOrder.compare(a.folder, b.folder) }
        assertEquals(listOf("/A/Series/Folge 1", "/A/Series/Folge 2", "/A/Series/Folge 10"), drafts.map { it.folder })
        assertEquals(2, drafts[0].files.size)
    }

    @Test
    fun `disc folders become one book in their parent`() {
        val files = listOf(
            f("/A/Book/CD2/01.mp3"), f("/A/Book/CD1/02.mp3"), f("/A/Book/CD1/01.mp3"),
        )
        val drafts = Grouping.group(files)
        assertEquals(1, drafts.size)
        assertEquals("/A/Book", drafts[0].folder)
        assertEquals(listOf("/A/Book/CD1/01.mp3", "/A/Book/CD1/02.mp3", "/A/Book/CD2/01.mp3"), drafts[0].files.map { it.path })
    }

    @Test
    fun `sibling folders tagged as discs of one album merge`() {
        val files = listOf(
            f("/A/Book/a/01.mp3", "Dune", 1, 1), f("/A/Book/a/02.mp3", "Dune", 2, 1),
            f("/A/Book/b/01.mp3", "Dune", 1, 2),
        )
        val drafts = Grouping.group(files)
        assertEquals(1, drafts.size)
        assertEquals(listOf(1, 1, 2), drafts[0].files.map { it.disc })
    }

    @Test
    fun `episodes sharing a series album tag stay apart`() {
        // Every episode tagged with the series as album, no disc numbers: still one book per folder.
        val files = listOf(
            f("/A/S/E1/01.mp3", "Series", 1), f("/A/S/E1/02.mp3", "Series", 2),
            f("/A/S/E2/01.mp3", "Series", 1), f("/A/S/E2/02.mp3", "Series", 2),
        )
        assertEquals(2, Grouping.group(files).size)
    }

    @Test
    fun `a folder with two albums splits`() {
        val files = listOf(
            f("/A/Mixed/a1.mp3", "One"), f("/A/Mixed/a2.mp3", "One"),
            f("/A/Mixed/b1.mp3", "Two"), f("/A/Mixed/b2.mp3", "Two"),
        )
        assertEquals(2, Grouping.group(files).size)
    }

    @Test
    fun `a stray album tag does not split a folder`() {
        val files = listOf(f("/A/B/1.mp3", "X"), f("/A/B/2.mp3", "Y"), f("/A/B/3.mp3"))
        assertEquals(1, Grouping.group(files).size)
    }

    @Test
    fun `loose m4b files are books each`() {
        val files = listOf(f("/A/one.m4b"), f("/A/two.m4b"), f("/A/three.m4b"))
        assertEquals(3, Grouping.group(files).size)
    }

    @Test
    fun `track numbers order, then names naturally`() {
        val sorted = Grouping.sortTracks(listOf(f("/b/Track 10.mp3"), f("/b/Track 2.mp3"), f("/b/Track 1.mp3")))
        assertEquals(listOf("/b/Track 1.mp3", "/b/Track 2.mp3", "/b/Track 10.mp3"), sorted.map { it.path })
        val tagged = Grouping.sortTracks(listOf(f("/b/z.mp3", track = 1), f("/b/a.mp3", track = 2)))
        assertEquals(listOf("/b/z.mp3", "/b/a.mp3"), tagged.map { it.path })
    }

    @Test
    fun `book clock spans files and finds chapters`() {
        val book = Book(
            "id", "/f", "T", "", "", "", "",
            listOf(
                Track("/f/1", "One", 10_000, listOf(Mark("A", 0), Mark("B", 4_000))),
                Track("/f/2", "Two", 5_000),
            ),
            null, "",
        )
        assertEquals(15_000, book.durationMs)
        assertEquals(listOf("A", "B", "Two"), book.chapters.map { it.title })
        assertEquals(1, book.chapterAt(5_000))
        assertEquals(2, book.chapterAt(12_000))
        assertEquals(1 to 2_000L, book.locate(12_000))
    }
}
