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
                draft = o.optString("draft"), edited = o.optBoolean("edited"),
            )
        }
    }.getOrDefault(emptyList())

    fun saveLibrary(books: List<Book>) {
        val array = JSONArray()
        books.forEach { b ->
            array.put(JSONObject().apply {
                put("id", b.id); put("folder", b.folder); put("title", b.title)
                put("author", b.author); put("narrator", b.narrator); put("year", b.year); put("series", b.series)
                put("cover", b.cover ?: ""); put("fp", b.fingerprint); put("draft", b.draft)
                if (b.edited) put("edited", true)
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

    private val overridesFile = File(dir, "overrides.json")

    fun loadOverrides(): Overrides = runCatching {
        if (!overridesFile.exists()) return Overrides()
        val o = JSONObject(overridesFile.readText())
        fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
        val merges = o.optJSONObject("merges")?.let { m -> m.keys().asSequence().associateWith { strings(m.getJSONArray(it)).toSet() } }.orEmpty()
        val edits = o.optJSONObject("edits")?.let { e ->
            e.keys().asSequence().associateWith { id ->
                val x = e.getJSONObject(id)
                fun field(name: String) = if (x.has(name)) x.getString(name) else null
                Edit(field("title"), field("author"), field("narrator"), field("series"))
            }
        }.orEmpty()
        Overrides(merges, strings(o.optJSONArray("perFile")).toSet(), strings(o.optJSONArray("keepApart")).toSet(), edits)
    }.getOrDefault(Overrides())

    fun saveOverrides(o: Overrides) {
        val json = JSONObject()
        json.put("merges", JSONObject().apply { o.merges.forEach { (k, v) -> put(k, JSONArray(v.sorted())) } })
        json.put("perFile", JSONArray(o.perFile.sorted()))
        json.put("keepApart", JSONArray(o.keepApart.sorted()))
        json.put("edits", JSONObject().apply {
            o.edits.forEach { (id, e) ->
                put(id, JSONObject().apply {
                    e.title?.let { put("title", it) }
                    e.author?.let { put("author", it) }
                    e.narrator?.let { put("narrator", it) }
                    e.series?.let { put("series", it) }
                })
            }
        })
        writeAtomically(overridesFile, json.toString(2))
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
