package io.github.carnager.stories.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

    private val _replaced = MutableSharedFlow<Set<String>>(extraBufferCapacity = 8)

    /** Books whose place was set from outside the player: imported, or marked finished or new. */
    val replaced: SharedFlow<Set<String>> = _replaced

    private val _scanning = MutableStateFlow<Scanner.Progress?>(null)
    val scanning: StateFlow<Scanner.Progress?> = _scanning.asStateFlow()

    @Volatile private var index = FolderIndex(_books.value, settings.value.roots)
    private var scanJob: Job? = null

    @Volatile private var byId = _books.value.associateBy { it.id }

    fun book(id: String?): Book? = id?.let { byId[it] }

    private val _overrides = MutableStateFlow(store.loadOverrides())
    @Volatile private var again = false

    /** Looks through the folders again; asked while looking, it looks once more after. */
    fun rescan() {
        if (scanJob?.isActive == true) { again = true; return }
        scanJob = scope.launch(Dispatchers.IO) {
            _scanning.value = Scanner.Progress(0, 0)
            try {
                do {
                    again = false
                    val roots = settings.value.roots
                    val before = byId
                    val found = scanner.scan(roots, _overrides.value) { _scanning.value = it }
                        .sortedWith { a, b -> NaturalOrder.compare(a.folder + "/" + a.title, b.folder + "/" + b.title) }
                    val moved = adoptMoved(found, before)
                    importFolderPositions(found)
                    index = FolderIndex(found, roots)
                    byId = found.associateBy { it.id }
                    _books.value = found
                    store.saveLibrary(found)
                    if (moved.isNotEmpty()) _replaced.tryEmit(moved)
                } while (again)
            } finally {
                _scanning.value = null
            }
        }
    }

    /**
     * Progress of books that changed shape follows them, by their files: a
     * book moved or renamed (the same files), books joined into one (to the
     * place in the file last heard), a book cut apart (the pieces before the
     * place finished, the piece holding it at that place). Old entries stay,
     * in case their folder comes back -- a card taken out, say.
     */
    private fun adoptMoved(found: List<Book>, before: Map<String, Book>): Set<String> {
        val ids = found.mapTo(HashSet()) { it.id }
        val byPrint = found.groupBy { it.fingerprint }
        val byPath = HashMap<String, Book>().apply { found.forEach { b -> b.tracks.forEach { put(it.path, b) } } }
        val all = _progress.value
        val adopted = HashMap<String, Progress>()
        fun offer(p: Progress?) {
            if (p == null) return
            val known = adopted[p.bookId] ?: all[p.bookId]
            if (known == null || known.lastPlayed < p.lastPlayed) adopted[p.bookId] = p
        }
        fun done(b: Book, from: Progress) = Progress(
            b.id, b.fingerprint, b.tracks.lastIndex, b.tracks.lastOrNull()?.durationMs ?: 0, b.durationMs, b.durationMs,
            true, from.speed, from.lastPlayed,
        )
        for ((id, p) in all) {
            if (id in ids) continue
            byPrint[p.fingerprint]?.singleOrNull()?.let { offer(p.copy(bookId = it.id)); continue }
            val old = before[id] ?: continue
            val oldPaths = old.tracks.map { it.path }
            val oldSet = oldPaths.toHashSet()
            val pieces = found.filter { b -> b.tracks.isNotEmpty() && b.tracks.all { it.path in oldSet } }
            for (b in pieces) {
                val at = b.tracks.map { oldPaths.indexOf(it.path) }
                when {
                    p.finished || at.max() < p.trackIndex -> offer(done(b, p))
                    p.trackIndex in at -> offer(placeAt(b, oldPaths[p.trackIndex].substringAfterLast('/'), p.trackPositionMs, false, p.speed, p.lastPlayed))
                }
            }
            // Joined into a bigger book: the place in the file, or the end of the last file once finished.
            val file = if (p.finished) oldPaths.lastOrNull() else oldPaths.getOrNull(p.trackIndex)
            val target = file?.let(byPath::get)?.takeIf { it !in pieces } ?: continue
            val fileMs = if (p.finished) old.tracks.last().durationMs else p.trackPositionMs
            offer(placeAt(target, file.substringAfterLast('/'), fileMs, false, p.speed, p.lastPlayed))
        }
        if (adopted.isEmpty()) return emptySet()
        _progress.update { it + adopted }
        persistProgress()
        return adopted.keys
    }

    // --- corrections by hand ---

    private fun correct(change: (Overrides) -> Overrides) {
        _overrides.update(change)
        val o = _overrides.value
        scope.launch(Dispatchers.IO) { writes.withLock { store.saveOverrides(o) } }
        rescan()
    }

    /** Joins books into one, in the order the folders give them. */
    fun merge(books: List<Book>) {
        val ids = books.map { it.draft }.filter { it.isNotEmpty() }.toSet()
        if (ids.size < 2) return
        correct { o ->
            // Books that were joined before are taken apart into what they were made of.
            val parts = ids.flatMap { id -> o.merges[id.substringAfter('#')] ?: setOf(id) }.toSet()
            val stale = ids.map { it.substringAfter('#') }.filter { it in o.merges }
            o.copy(merges = o.merges - stale.toSet() + (Grouping.mergeKey(parts) to parts))
        }
    }

    /** Undoes a join: by hand, or of disc folders. */
    fun unmerge(book: Book) = correct { o ->
        val key = book.draft.substringAfter('#')
        if (book.joined) o.copy(merges = o.merges - key) else o.copy(keepApart = o.keepApart + book.folder)
    }

    /** One book per file, for a folder of stories that aren't one book. */
    fun cutPerFile(book: Book) = correct { o -> o.copy(perFile = o.perFile + book.draft) }

    /** Gathers a folder's files back into one book, undoing a cut. */
    fun uncut(folder: String) = correct { o -> o.copy(perFile = o.perFile.filterNot { it.startsWith("$folder#") }.toSet()) }

    /** Whether files in this folder were cut into one book each by hand. */
    fun isCut(folder: String) = _overrides.value.perFile.any { it.startsWith("$folder#") }

    /** Details by hand: shown at once, kept over rescans; null puts the tags' back. */
    fun edit(book: Book, edit: Edit?) {
        if (edit == null) {
            correct { o -> o.copy(edits = o.edits - book.id) }
            return
        }
        _overrides.update { it.copy(edits = it.edits + (book.id to edit)) }
        val o = _overrides.value
        val changed = book.with(edit)
        _books.update { all -> all.map { if (it.id == book.id) changed else it } }
        byId = _books.value.associateBy { it.id }
        index = FolderIndex(_books.value, settings.value.roots)
        val snapshot = _books.value
        scope.launch(Dispatchers.IO) { writes.withLock { store.saveOverrides(o); store.saveLibrary(snapshot) } }
    }

    fun editOf(book: Book): Edit? = _overrides.value.edits[book.id]

    /**
     * Where Smart AudioBook Player left each book, from the position file it
     * keeps in the book's folder, and where Stories on another device left
     * it, from its own. Each taken when newer than what's here, so listening
     * on elsewhere carries over.
     */
    private fun importFolderPositions(found: List<Book>) {
        val imported = HashMap<String, Progress>()
        fun take(book: Book, p: Progress?) {
            if (p == null) return
            val known = imported[book.id] ?: _progress.value[book.id]
            if (known != null && known.lastPlayed >= p.lastPlayed) return
            if (!p.finished && p.positionMs <= 0 && known == null) return
            imported[book.id] = p
        }
        for ((folder, books) in found.groupBy { it.folder }) {
            File(folder, SabpReader.FILE_NAME).takeIf { it.isFile }?.let(SabpReader::read)?.let { sabp ->
                // Seconds, as far as seen; read as milliseconds should seconds overrun the file.
                val book = books.firstOrNull { b -> b.tracks.any { it.path.substringAfterLast('/') == sabp.fileName } } ?: return@let
                val track = book.tracks.first { it.path.substringAfterLast('/') == sabp.fileName }
                val inTrack = sabp.fileSeconds * 1000L
                val fileMs = if (track.durationMs > 0 && inTrack > track.durationMs + 60_000 && sabp.fileSeconds <= track.durationMs) sabp.fileSeconds.toLong() else inTrack
                val finished = sabp.state.equals("Finished", true) || sabp.state.equals("Completed", true)
                take(book, placeAt(book, sabp.fileName, fileMs, finished, sabp.speed, sabp.lastPlayed))
            }
            val shared = FolderPositions.read(folder)
            for (book in books) {
                val e = shared[book.fingerprint] ?: continue
                take(book, placeAt(book, e.fileName, e.fileMs, e.finished, e.speed, e.lastPlayed))
            }
        }
        if (imported.isEmpty()) return
        _progress.update { it + imported }
        persistProgress()
        _replaced.tryEmit(imported.keys)
    }

    private fun placeAt(book: Book, fileName: String, fileMs: Long, finished: Boolean, speed: Float, lastPlayed: Long): Progress? {
        val index = book.tracks.indexOfFirst { it.path.substringAfterLast('/') == fileName }
        if (index < 0) return null
        val position = if (finished) book.durationMs else (book.trackStarts[index] + fileMs).coerceAtMost(book.durationMs)
        return Progress(
            bookId = book.id, fingerprint = book.fingerprint, trackIndex = index, trackPositionMs = fileMs,
            positionMs = position, durationMs = book.durationMs, finished = finished,
            speed = speed, lastPlayed = lastPlayed,
        )
    }

    /** Into the book's folder, for other devices, when the setting asks for it. */
    private fun share(ids: Collection<String>) {
        if (!settings.value.shareInFolders) return
        val books = ids.mapNotNull { id -> book(id)?.let { it to _progress.value[id] } }
        scope.launch(Dispatchers.IO) {
            books.forEach { (book, p) -> if (p != null) FolderPositions.write(book, p) }
        }
    }

    fun entries(folder: String?): List<Entry> = index.entries(folder)

    /** The folders to open, from the top, to see this book listed. */
    fun pathTo(book: Book): List<String> {
        val root = settings.value.roots.map { it.trimEnd('/') }
            .filter { book.folder == it || book.folder.startsWith("$it/") }.maxByOrNull { it.length } ?: return emptyList()
        val listed = index.listedIn(book)
        val steps = ArrayList<String>()
        var dir = listed
        while (dir.length > root.length) { steps.add(0, dir); dir = dir.substringBeforeLast('/') }
        // Several chosen folders: their list is the top, so the root itself is a step.
        if (settings.value.roots.size > 1) steps.add(0, root)
        return steps
    }
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

    /** [shared] when the book stopped: then its place also goes into its folder, if that's wanted. */
    fun setProgress(p: Progress, persist: Boolean, shared: Boolean = false) {
        _progress.update { it + (p.bookId to p) }
        if (persist) persistProgress()
        if (shared) share(listOf(p.bookId))
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
        share(books.map { it.id })
        _replaced.tryEmit(books.mapTo(HashSet()) { it.id })
    }

    fun addBookmark(bookId: String, positionMs: Long, note: String): Long {
        val id = System.currentTimeMillis()
        _bookmarks.update { it + Bookmark(id, bookId, positionMs, note, id) }
        persistBookmarks()
        return id
    }

    fun noteBookmark(id: Long, note: String) {
        _bookmarks.update { all -> all.map { if (it.id == id) it.copy(note = note.trim()) else it } }
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
