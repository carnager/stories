package io.github.carnager.stories.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** One row of the folder view: a folder holding books further down, or a book. */
sealed interface Entry {
    val name: String

    data class Folder(val path: String, override val name: String, val books: List<Book>) : Entry
    data class Item(val book: Book, override val name: String) : Entry
}

/**
 * The books, what has been heard of them, and the folders they sit in.
 * Everything here is a flow the screens watch; writes go to disk off the
 * main thread, newest last.
 */
class Library(dir: File, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val store = Store(dir)
    private val scanner = Scanner(dir)
    private val writes = Mutex()

    private val _books = MutableStateFlow(store.loadLibrary())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val _progress = MutableStateFlow(store.loadProgress())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    private val _bookmarks = MutableStateFlow(store.loadBookmarks())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _scanning = MutableStateFlow<Scanner.Progress?>(null)
    val scanning: StateFlow<Scanner.Progress?> = _scanning.asStateFlow()

    @Volatile private var index = FolderIndex(_books.value, settings.value.roots)
    private var scanJob: Job? = null

    @Volatile private var byId = _books.value.associateBy { it.id }

    fun book(id: String?): Book? = id?.let { byId[it] }

    fun rescan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch(Dispatchers.IO) {
            _scanning.value = Scanner.Progress(0, 0)
            try {
                val roots = settings.value.roots
                val found = scanner.scan(roots) { _scanning.value = it }
                    .sortedWith { a, b -> NaturalOrder.compare(a.folder + "/" + a.title, b.folder + "/" + b.title) }
                adoptMoved(found)
                importSabp(found)
                index = FolderIndex(found, roots)
                byId = found.associateBy { it.id }
                _books.value = found
                store.saveLibrary(found)
            } finally {
                _scanning.value = null
            }
        }
    }

    /** Progress of a book that moved or was renamed follows it, found by its files. */
    private fun adoptMoved(found: List<Book>) {
        val ids = found.mapTo(HashSet()) { it.id }
        val byPrint = found.groupBy { it.fingerprint }
        var changed = false
        _progress.update { all ->
            val result = all.toMutableMap()
            for ((id, p) in all) {
                if (id in ids) continue
                val match = byPrint[p.fingerprint]?.singleOrNull() ?: continue
                if (match.id in result) continue
                result.remove(id)
                result[match.id] = p.copy(bookId = match.id)
                changed = true
            }
            result
        }
        if (changed) persistProgress()
    }

    /**
     * Where Smart AudioBook Player left each book, from the position file it
     * keeps in the book's folder. Taken when it's newer than what's here, so
     * listening on in either app carries over.
     */
    private fun importSabp(found: List<Book>) {
        val imported = HashMap<String, Progress>()
        for (book in found) {
            val file = File(book.folder, SabpReader.FILE_NAME)
            if (!file.isFile) continue
            val sabp = SabpReader.read(file) ?: continue
            val known = _progress.value[book.id]
            if (known != null && known.lastPlayed >= sabp.lastPlayed) continue
            val index = book.tracks.indexOfFirst { it.path.substringAfterLast('/') == sabp.fileName }
            // A folder split into several books: the file names which one.
            if (index < 0) continue
            val track = book.tracks[index]
            // Seconds, as far as seen; read as milliseconds should seconds overrun the file.
            val inTrack = sabp.fileSeconds * 1000L
            val trackMs = if (track.durationMs > 0 && inTrack > track.durationMs + 60_000 && sabp.fileSeconds <= track.durationMs) sabp.fileSeconds.toLong() else inTrack
            val finished = sabp.state.equals("Finished", true) || sabp.state.equals("Completed", true)
            val position = if (finished) book.durationMs else (book.trackStarts[index] + trackMs).coerceAtMost(book.durationMs)
            if (!finished && position <= 0 && known == null) continue
            imported[book.id] = Progress(
                bookId = book.id, fingerprint = book.fingerprint, trackIndex = index, trackPositionMs = trackMs,
                positionMs = position, durationMs = book.durationMs, finished = finished,
                speed = sabp.speed, lastPlayed = sabp.lastPlayed,
            )
        }
        if (imported.isEmpty()) return
        _progress.update { it + imported }
        persistProgress()
    }

    fun entries(folder: String?): List<Entry> = index.entries(folder)
    fun folderBooks(folder: String): List<Book> = index.booksUnder(folder)
    fun rootFolder(): String? = index.single()
    fun parentOf(folder: String): String? = index.parentOf(folder)

    /** The next book beside this one that isn't finished: a series' next episode. */
    fun nextInSeries(book: Book): Book? {
        val siblings = index.entries(index.listedIn(book)).filterIsInstance<Entry.Item>().map { it.book }
        val at = siblings.indexOfFirst { it.id == book.id }
        if (at < 0) return null
        return siblings.drop(at + 1).firstOrNull { _progress.value[it.id].status() != Status.Finished }
    }

    fun setProgress(p: Progress, persist: Boolean) {
        _progress.update { it + (p.bookId to p) }
        if (persist) persistProgress()
    }

    fun markFinished(books: List<Book>, finished: Boolean) {
        val now = System.currentTimeMillis()
        _progress.update { all ->
            val result = all.toMutableMap()
            books.forEach { book ->
                val old = all[book.id]
                result[book.id] = if (finished) {
                    Progress(book.id, book.fingerprint, old?.trackIndex ?: 0, old?.trackPositionMs ?: 0, book.durationMs, book.durationMs,
                        true, old?.speed ?: 1f, old?.lastPlayed ?: now)
                } else {
                    Progress(book.id, book.fingerprint, 0, 0, 0, book.durationMs, false, old?.speed ?: 1f, 0)
                }
            }
            result
        }
        persistProgress()
    }

    fun addBookmark(bookId: String, positionMs: Long, note: String) {
        _bookmarks.update { it + Bookmark(System.currentTimeMillis(), bookId, positionMs, note, System.currentTimeMillis()) }
        persistBookmarks()
    }

    fun removeBookmark(id: Long) {
        _bookmarks.update { all -> all.filter { it.id != id } }
        persistBookmarks()
    }

    fun export(): String = store.export()

    // Each write takes what is newest when its turn comes, so writes finishing out of order can't go back in time.
    private fun persistProgress() {
        scope.launch(Dispatchers.IO) { writes.withLock { store.saveProgress(_progress.value.values.toList()) } }
    }

    private fun persistBookmarks() {
        scope.launch(Dispatchers.IO) { writes.withLock { store.saveBookmarks(_bookmarks.value) } }
    }

    /** Written now, on this thread: for when the process may be about to go. */
    fun flush() = runCatching { store.saveProgress(_progress.value.values.toList()) }
}

/**
 * Which folders hold which books, worked out once per scan. A folder that
 * is one book and nothing more shows as that book; a folder with books
 * further down shows as a folder, with what's heard of them all.
 */
private class FolderIndex(books: List<Book>, roots: List<String>) {
    private val roots = roots.map { it.trimEnd('/') }
    private val booksAt = books.groupBy { it.folder }
    private val under = HashMap<String, MutableList<Book>>()
    private val children = HashMap<String, MutableSet<String>>()

    init {
        for (book in books) {
            val root = this.roots.filter { book.folder == it || book.folder.startsWith("$it/") }.maxByOrNull { it.length } ?: continue
            var dir = book.folder
            while (true) {
                under.getOrPut(dir) { ArrayList() } += book
                if (dir == root) break
                val parent = dir.substringBeforeLast('/')
                children.getOrPut(parent) { HashSet() } += dir
                dir = parent
            }
        }
    }

    fun single(): String? = roots.singleOrNull()

    fun parentOf(folder: String): String? = if (folder in roots) null else folder.substringBeforeLast('/')

    fun booksUnder(folder: String): List<Book> = under[folder].orEmpty()

    /** The folder whose listing shows this book. */
    fun listedIn(book: Book): String {
        val alone = booksAt[book.folder]?.size == 1 && children[book.folder].isNullOrEmpty()
        return if (alone && book.folder !in roots) book.folder.substringBeforeLast('/') else book.folder
    }

    fun entries(folder: String?): List<Entry> {
        if (folder == null) {
            return roots.filter { under[it] != null }.map { Entry.Folder(it, it.substringAfterLast('/'), under[it].orEmpty()) }
        }
        val result = ArrayList<Entry>()
        // The folder's own books first, as a folder of loose .m4b files has them.
        booksAt[folder].orEmpty().forEach { result += Entry.Item(it, it.title) }
        children[folder].orEmpty().sortedWith(NaturalOrder).forEach { child ->
            val name = child.substringAfterLast('/')
            val own = booksAt[child].orEmpty()
            val deeper = children[child].orEmpty().isNotEmpty()
            result += if (own.size == 1 && !deeper) Entry.Item(own.first(), name) else Entry.Folder(child, name, under[child].orEmpty())
        }
        return result
    }
}
