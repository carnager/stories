package io.github.carnager.stories.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against a real storiesd, when STORIES_TEST_SERVER names one and
 * STORIES_TEST_PASSWORD its password. Writes positions and bookmarks to it,
 * so point it at a throwaway server.
 */
class SyncApiTest {
    @Test
    fun `talks to a real server`() {
        val url = System.getenv("STORIES_TEST_SERVER")
        val password = System.getenv("STORIES_TEST_PASSWORD")
        assumeTrue(url != null && password != null)

        assertThrows(WrongPassword::class.java) { SyncApi(url!!).login("not the password", "test") }
        val login = SyncApi(url!!).login(password!!, "api test")
        val api = SyncApi(url, login.token)

        val now = System.currentTimeMillis()
        assertTrue(kotlin.math.abs(api.serverNow() - now) < 60_000)

        val (etag, books) = api.library(null)!!
        val files = books.flatMap { it.files }.map { ServerFile(it.path, it.size) }
        assertTrue(files.isNotEmpty())
        assertTrue(books.all { b -> b.title.isNotEmpty() && b.files.all { it.id.startsWith("f_") && it.durationMs > 0 } })
        assertEquals(books, books.map { RemoteBook.of(org.json.JSONObject(it.json().toString())) })
        assertEquals(null, api.library(etag))

        val file = files.first()
        val (startRev, _) = api.positions(0)
        api.putPositions(now, listOf(RemotePosition(file.path, file.size, 4_000, false, 1.25, now)))
        val (rev, changed) = api.positions(startRev)
        assertTrue(rev > startRev)
        assertEquals(4_000L, changed.single().ms)
        assertEquals(1.25, changed.single().speed, 0.001)

        val id = "api-test-$now"
        val (markRev, _) = api.bookmarks(0)
        api.putBookmarks(now, listOf(RemoteBookmark(id, file.path, file.size, 1_000, "här", now, now, false)))
        assertEquals("här", api.bookmarks(markRev).second.single { it.id == id }.note)

        assertThrows(SignedOut::class.java) { SyncApi(url, "not-a-token").serverNow() }
        assertThrows(NotAllowed::class.java) { SyncApi("http://8.8.8.8:8743", login.token).serverNow() }
    }
}
