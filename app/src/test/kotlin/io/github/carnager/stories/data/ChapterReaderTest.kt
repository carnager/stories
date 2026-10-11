package io.github.carnager.stories.data

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs against real files when STORIES_SAMPLES names a folder of them; prints what it finds. */
class ChapterReaderTest {
    @Test
    fun `reads chapters of sample files`() {
        val dir = System.getenv("STORIES_SAMPLES")?.let(::File)
        assumeTrue(dir?.isDirectory == true)
        dir!!.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("m4b", "m4a", "mp3") }.forEach { f ->
            val marks = ChapterReader.read(f)
            if (marks.isNotEmpty()) println("${f.name}: " + marks.joinToString { "${it.startMs}=${it.title}" })
        }
    }
}
