package io.github.carnager.stories.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The app's files: the library as last scanned, so it shows at once on
 * start, and what has been heard of each book. Plain JSON, written whole
 * and atomically -- a phone killed mid-write keeps the previous copy.
 */
class Store(private val dir: File) {
    private val libraryFile = File(dir, "library.json")
    private val progressFile = File(dir, "progress.json")
    private val bookmarksFile = File(dir, "bookmarks.json")

    fun loadLibrary(): List<Book> = runCatching {
        if (!libraryFile.exists()) return emptyList()
        val array = JSONArray(libraryFile.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val tracks = o.getJSONArray("tracks").let { a ->
                (0 until a.length()).map { j ->
                    val t = a.getJSONObject(j)
                    val marks = t.optJSONArray("marks")?.let { m ->
                        (0 until m.length()).map { k -> m.getJSONObject(k).let { Mark(it.getString("t"), it.getLong("s")) } }
                    }.orEmpty()
                    Track(t.getString("path"), t.getString("title"), t.getLong("dur"), marks)
                }
            }
            Book(
                id = o.getString("id"), folder = o.getString("folder"), title = o.getString("title"),
                author = o.optString("author"), narrator = o.optString("narrator"), year = o.optString("year"),
                series = o.optString("series"), tracks = tracks,
                cover = o.optString("cover").ifEmpty { null }, fingerprint = o.optString("fp"),
            )
        }
    }.getOrDefault(emptyList())

    fun saveLibrary(books: List<Book>) {
        val array = JSONArray()
        books.forEach { b ->
            array.put(JSONObject().apply {
                put("id", b.id); put("folder", b.folder); put("title", b.title)
                put("author", b.author); put("narrator", b.narrator); put("year", b.year); put("series", b.series)
                put("cover", b.cover ?: ""); put("fp", b.fingerprint)
                put("tracks", JSONArray().apply {
                    b.tracks.forEach { t ->
                        put(JSONObject().apply {
                            put("path", t.path); put("title", t.title); put("dur", t.durationMs)
                            if (t.marks.isNotEmpty()) put("marks", JSONArray().apply {
                                t.marks.forEach { put(JSONObject().put("t", it.title).put("s", it.startMs)) }
                            })
                        })
                    }
                })
            })
        }
        writeAtomically(libraryFile, array.toString())
    }

    fun loadProgress(): Map<String, Progress> = runCatching {
        if (!progressFile.exists()) return emptyMap()
        val array = JSONArray(progressFile.readText())
        (0 until array.length()).associate { i ->
            val o = array.getJSONObject(i)
            val p = Progress(
                bookId = o.getString("id"), fingerprint = o.optString("fp"),
                trackIndex = o.optInt("track"), trackPositionMs = o.optLong("tpos"), positionMs = o.optLong("pos"),
                durationMs = o.optLong("dur"), finished = o.optBoolean("done"),
                speed = o.optDouble("speed", 1.0).toFloat(), lastPlayed = o.optLong("played"),
            )
            p.bookId to p
        }
    }.getOrDefault(emptyMap())

    fun saveProgress(all: Collection<Progress>) {
        val array = JSONArray()
        all.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.bookId); put("fp", p.fingerprint); put("track", p.trackIndex); put("tpos", p.trackPositionMs)
                put("pos", p.positionMs); put("dur", p.durationMs); put("done", p.finished)
                put("speed", p.speed.toDouble()); put("played", p.lastPlayed)
            })
        }
        writeAtomically(progressFile, array.toString())
    }

    fun loadBookmarks(): List<Bookmark> = runCatching {
        if (!bookmarksFile.exists()) return emptyList()
        val array = JSONArray(bookmarksFile.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Bookmark(o.getLong("id"), o.getString("book"), o.getLong("pos"), o.optString("note"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    fun saveBookmarks(all: List<Bookmark>) {
        val array = JSONArray()
        all.forEach { b ->
            array.put(JSONObject().put("id", b.id).put("book", b.bookId).put("pos", b.positionMs).put("note", b.note).put("at", b.created))
        }
        writeAtomically(bookmarksFile, array.toString())
    }

    /** Everything heard, as one file to keep or move to another phone. */
    fun export(): String = JSONObject()
        .put("progress", JSONArray(if (progressFile.exists()) progressFile.readText() else "[]"))
        .put("bookmarks", JSONArray(if (bookmarksFile.exists()) bookmarksFile.readText() else "[]"))
        .toString(2)

    companion object {
        fun writeAtomically(file: File, text: String) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }
}
