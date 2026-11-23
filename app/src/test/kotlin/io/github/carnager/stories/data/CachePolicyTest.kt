package io.github.carnager.stories.data

import io.github.carnager.stories.data.CachePolicy.CachedBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {
    private fun book(id: String, bytes: Long, finished: Boolean = true, played: Long = 0, loaded: Boolean = false) =
        CachedBook(id, bytes, finished, played, loaded)

    @Test
    fun `under the limit nothing goes`() {
        val plan = CachePolicy.plan(listOf(book("a", 40)), limit = 100, needed = 60)
        assertTrue(plan.fits)
        assertEquals(emptyList<String>(), plan.evict)
    }

    @Test
    fun `finished books go, longest ago first, and no more than needed`() {
        val plan = CachePolicy.plan(listOf(book("new", 40, played = 30), book("old", 40, played = 10), book("mid", 40, played = 20)), 120, 30)
        assertTrue(plan.fits)
        assertEquals(listOf("old"), plan.evict)
    }

    @Test
    fun `books in progress, in the player, or being fetched stay`() {
        val books = listOf(book("going", 50, finished = false), book("loaded", 50, loaded = true), book("target", 50))
        val plan = CachePolicy.plan(books, 100, 60, target = "target")
        assertFalse(plan.fits)
        assertEquals(emptyList<String>(), plan.evict)
    }

    @Test
    fun `nothing is removed when it would not make it fit`() {
        val plan = CachePolicy.plan(listOf(book("done", 10), book("going", 90, finished = false)), 100, 50)
        assertFalse(plan.fits)
        assertEquals(emptyList<String>(), plan.evict)
    }

    @Test
    fun `a lowered limit gets as close as it can`() {
        val plan = CachePolicy.plan(listOf(book("done", 10), book("going", 90, finished = false)), 50, 0, partial = true)
        assertFalse(plan.fits)
        assertEquals(listOf("done"), plan.evict)
    }

    private val server = mapOf("f_new" to "A/01.mp3", "f_b" to "A/02.mp3")

    @Test
    fun `a replaced file's copy goes at once`() {
        val s = CachePolicy.sweep(setOf("f_old", "f_b"), mapOf("f_old" to "A/01.mp3"), server, emptyMap(), emptySet(), 1000)
        assertEquals(setOf("f_old"), s.delete)
        assertTrue(s.waiting.isEmpty())
    }

    @Test
    fun `a file gone from the server waits, then goes`() {
        val paths = mapOf("f_gone" to "B/01.mp3")
        val first = CachePolicy.sweep(setOf("f_gone"), paths, server, emptyMap(), emptySet(), 1000)
        assertTrue(first.delete.isEmpty())
        assertEquals(1000L, first.waiting["f_gone"])
        val early = CachePolicy.sweep(setOf("f_gone"), paths, server, first.waiting, emptySet(), 1000 + CachePolicy.GRACE_MS - 1)
        assertTrue(early.delete.isEmpty())
        val late = CachePolicy.sweep(setOf("f_gone"), paths, server, first.waiting, emptySet(), 1000 + CachePolicy.GRACE_MS)
        assertEquals(setOf("f_gone"), late.delete)
    }

    @Test
    fun `a file back on the server stops waiting`() {
        val s = CachePolicy.sweep(setOf("f_b"), mapOf("f_b" to "A/02.mp3"), server, mapOf("f_b" to 1L), emptySet(), 1000)
        assertTrue(s.delete.isEmpty())
        assertTrue(s.waiting.isEmpty())
    }

    @Test
    fun `an empty library deletes nothing`() {
        val s = CachePolicy.sweep(setOf("f_a"), mapOf("f_a" to "A/01.mp3"), emptyMap(), mapOf("f_a" to 0L), emptySet(), CachePolicy.GRACE_MS * 10)
        assertTrue(s.delete.isEmpty())
        assertEquals(mapOf("f_a" to 0L), s.waiting)
    }

    @Test
    fun `a copy in the player stays even when replaced`() {
        val s = CachePolicy.sweep(setOf("f_old"), mapOf("f_old" to "A/01.mp3"), server, emptyMap(), setOf("A/01.mp3"), 1000)
        assertTrue(s.delete.isEmpty())
        assertEquals(setOf("f_old"), s.waiting.keys)
    }
}
