package io.github.carnager.stories.data

import org.json.JSONObject
import java.io.File

/**
 * Stories' own place-keeping file, in a book's folder, for libraries synced
 * between devices: each book there by its fingerprint, with the file and
 * time it was at. Small, plain JSON, rewritten only when a book pauses.
 */
object FolderPositions {
    const val FILE_NAME = ".stories-position.json"

    data class Entry(
        val fileName: String,
        val fileMs: Long,
        val finished: Boolean,
        val speed: Float,
        val lastPlayed: Long,
    )

    fun read(folder: String): Map<String, Entry> = runCatching {
        val file = File(folder, FILE_NAME)
        if (!file.isFile) return emptyMap()
        val books = JSONObject(file.readText()).getJSONObject("books")
        books.keys().asSequence().associateWith { key ->
            val o = books.getJSONObject(key)
            Entry(o.getString("file"), o.optLong("fileMs"), o.optBoolean("finished"), o.optDouble("speed", 1.0).toFloat(), o.optLong("lastPlayed"))
        }
    }.getOrDefault(emptyMap())

    /** Writes [book]'s place into its folder's file, keeping the other books there. */
    fun write(book: Book, p: Progress) {
        val track = book.tracks.getOrNull(p.trackIndex) ?: return
        val file = File(book.folder, FILE_NAME)
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
        val books = root.optJSONObject("books") ?: JSONObject()
        books.put(book.fingerprint, JSONObject().apply {
            put("title", book.title)
            put("file", track.path.substringAfterLast('/'))
            put("fileMs", if (p.finished) track.durationMs else p.trackPositionMs)
            put("finished", p.finished)
            put("speed", p.speed.toDouble())
            put("lastPlayed", p.lastPlayed)
        })
        root.put("books", books)
        runCatching { Store.writeAtomically(file, root.toString(2)) }
    }
}
