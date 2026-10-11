package io.github.carnager.stories.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipTest {
    @Test
    fun `typed skip lengths`() {
        assertEquals(45, parseSkip("45"))
        assertEquals(45, parseSkip("45 s"))
        assertEquals(120, parseSkip("2m"))
        assertEquals(120, parseSkip("2 min"))
        assertEquals(90, parseSkip("1:30"))
        assertEquals(null, parseSkip("soon"))
    }

    @Test
    fun `skip lengths shown`() {
        assertEquals("30", shortSpan(30))
        assertEquals("5m", shortSpan(300))
        assertEquals("1:30", shortSpan(90))
        assertEquals("1 min 30 s", skipLabel(90))
    }
}
