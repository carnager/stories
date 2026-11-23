package io.github.carnager.stories.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** How much of a server book is on the device. */
data class CacheState(val done: Long, val total: Long, val kept: Boolean, val error: String? = null) {
    val complete: Boolean get() = total > 0 && done >= total
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * The server's books, as books of the library under a folder of their own.
 * Each plays from its copy on the device when it has one, otherwise as a
 * stream. Copies live in the app's private storage: the user's audiobook
 * folders are never written to, and nothing on the server is ever changed.
 */
class Remote(
    dir: File,
    private val library: Library,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val root = File(dir, "server").apply { mkdirs() }
    private val covers = File(dir, "covers").apply { mkdirs() }
    private val file = File(dir, "server-library.json")

    @Volatile private var url: String? = null
    @Volatile private var token: String? = null
    @Volatile var serverId: String? = null
        private set

    @Volatile private var all: List<RemoteBook> = emptyList()
    @Volatile private var hidden: Set<String> = emptySet()
    @Volatile private var byTrack: Map<String, RemoteFile> = emptyMap()
    private val kept = MutableStateFlow<Set<String>>(emptySet())

    private val _cache = MutableStateFlow<Map<String, CacheState>>(emptyMap())
    val cache: StateFlow<Map<String, CacheState>> = _cache.asStateFlow()

    private val _active = MutableStateFlow(false)

    /** Bytes are moving: what the download service stays up for. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** The book in the player, and its files' server paths: never removed from under it. */
    @Volatile var loadedBook: () -> String? = { null }
    @Volatile var loadedPaths: () -> Set<String> = { emptySet() }

    // Where each copy came from, and since when those gone from the server have been gone.
    private val copyPaths = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val missingSince = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val kick = Channel<Unit>(Channel.CONFLATED)

    init {
        load()
        index()
        library.setRemote(visible())
        scope.launch(Dispatchers.IO) {
            refreshCache()
            for (ask in kick) runDownloads()
        }
    }

    fun signIn(url: String, token: String, serverId: String) {
        this.url = url
        this.token = token
        this.serverId = serverId
        resumeDownloads()
    }

    /** No more streaming or downloading. What's cached stays and still plays. */
    fun signOut() {
        url = null
        token = null
    }

    fun resumeDownloads() {
        kick.trySend(Unit)
    }

    // --- the books ---

    fun setBooks(books: List<RemoteBook>) {
        all = books.filter { b -> b.files.isNotEmpty() && b.files.all { safe(it.id) } }
        index()
    }

    fun files(): List<ServerFile> = all.flatMap { b -> b.files.map { ServerFile(it.path, it.size) } }

    /** Shows the server's books, except those wholly on the phone in its own folders. */
    fun publish(match: FileMatch) {
        hidden = all.filter { b -> b.files.all { match.local(ServerFile(it.path, it.size)) != null } }.mapTo(HashSet()) { it.id }
        library.setRemote(visible())
        save()
        refreshCache()
        fetchCovers()
    }

    private fun index() {
        byTrack = all.flatMap { b -> b.files.map { virtual(it.path) to it } }.toMap()
    }

    private fun visible(): List<Book> = all.filter { it.id !in hidden }.map(::toBook)

    private fun virtual(path: String) = "$SERVER_ROOT/$path"

    private fun toBook(b: RemoteBook): Book {
        val tracks = b.files.map { Track(virtual(it.path), it.title, it.durationMs, it.marks) }
        return Book(
            id = b.id,
            folder = Grouping.commonFolder(tracks.map { it.path.substringBeforeLast('/') }.distinct()),
            title = b.title, author = b.author, narrator = b.narrator, year = b.year, series = b.series,
            tracks = tracks,
            cover = coverFile(b)?.takeIf { it.isFile }?.path,
            fingerprint = Grouping.hash(b.files.map { "${it.path.substringAfterLast('/')}:${it.size}" }.sorted().joinToString("|")),
        )
    }

    /** The server's file for a track of a server book. */
    fun serverFile(trackPath: String): ServerFile? = byTrack[trackPath]?.let { ServerFile(it.path, it.size) }

    fun trackPath(f: ServerFile): String? = virtual(f.path).takeIf { byTrack[it]?.size == f.size }

    private fun copy(f: RemoteFile) = File(root, "${f.id}.audio")
    private fun part(f: RemoteFile) = File(root, "${f.id}.part")
    private fun complete(f: RemoteFile) = copy(f).let { it.isFile && it.length() == f.size }

    // Ids become file names and URLs, and come from the network.
    private fun safe(id: String) = id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun coverFile(b: RemoteBook) = b.coverId.takeIf(::safe)?.let { File(covers, "$it.jpg") }

    // --- playing ---

    /** Where to play a track from. Null for a track that isn't a server book's. */
    fun uri(track: Track): Uri? {
        val f = byTrack[track.path] ?: return null
        if (complete(f)) return Uri.fromFile(copy(f))
        val base = url ?: return Uri.fromFile(copy(f))
        return Uri.parse("$base/v1/files/${f.id}")
    }

    /** The player's sources: the token goes only to the server's own file URLs. */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun mediaSources(context: Context): MediaSource.Factory {
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(15_000)
        val upstream = DefaultDataSource.Factory(context, http)
        val signed = ResolvingDataSource.Factory(upstream) { spec ->
            val base = url
            val key = token
            if (base == null || key == null || !spec.uri.toString().startsWith("$base/v1/files/")) spec
            else {
                if (!ServerAddress.allowed(base)) throw NotAllowed()
                spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $key"))
            }
        }
        return DefaultMediaSourceFactory(signed)
    }

    // --- keeping on the device ---

    fun keep(book: Book) {
        kept.update { it + book.id }
        scope.launch(Dispatchers.IO) {
            save()
            refreshCache()
            kick.trySend(Unit)
        }
    }

    /** Takes the copy off the device. The book stays on the server, and in the library. */
    fun release(book: Book) {
        kept.update { it - book.id }
        scope.launch(Dispatchers.IO) {
            deleteCopies(book.id)
            save()
            refreshCache()
        }
    }

    private fun deleteCopies(bookId: String) {
        all.firstOrNull { it.id == bookId }?.files?.forEach {
            copy(it).delete()
            part(it).delete()
            copyPaths.remove(it.id)
        }
    }

    private fun limitBytes() = settings.value.cacheLimitGb * 1_000_000_000L

    private fun cachedBooks(): List<CachePolicy.CachedBook> {
        val progress = library.progress.value
        val loaded = loadedBook()
        return all.mapNotNull { b ->
            val bytes = b.files.sumOf { f -> if (complete(f)) f.size else part(f).length().coerceAtMost(f.size) }
            if (bytes == 0L) null
            else CachePolicy.CachedBook(b.id, bytes, progress[b.id]?.finished == true, progress[b.id]?.lastPlayed ?: 0, b.id == loaded)
        }
    }

    private fun evict(ids: List<String>) {
        if (ids.isEmpty()) return
        kept.update { it - ids.toSet() }
        ids.forEach(::deleteCopies)
        save()
    }

    /** Copies of files the server no longer has, by the rules in [CachePolicy.sweep]. */
    private fun sweep() {
        val onDisk = root.listFiles().orEmpty().filter { it.extension == "audio" || it.extension == "part" }
            .mapTo(HashSet()) { it.nameWithoutExtension }
        val server = all.flatMap { b -> b.files.map { it.id to it.path } }.toMap()
        val before = HashMap(missingSince)
        val result = CachePolicy.sweep(onDisk, HashMap(copyPaths), server, before, loadedPaths(), System.currentTimeMillis())
        for (id in result.delete) {
            File(root, "$id.audio").delete()
            File(root, "$id.part").delete()
            copyPaths.remove(id)
        }
        missingSince.clear()
        missingSince.putAll(result.waiting)
        if (result.delete.isNotEmpty() || result.waiting != before) save()
    }

    private fun refreshCache() {
        val wanted = kept.value
        _cache.update { old ->
            all.mapNotNull { b ->
                val total = b.files.sumOf { it.size }
                val done = b.files.sumOf { f -> if (complete(f)) f.size else part(f).length().coerceAtMost(f.size) }
                if (b.id !in wanted && done == 0L) null
                else b.id to CacheState(done, total, b.id in wanted, old[b.id]?.error)
            }.toMap()
        }
    }

    private fun runDownloads() {
        try {
            sweep()
            limitBytes().takeIf { it > 0 }?.let { evict(CachePolicy.plan(cachedBooks(), it, 0, partial = true).evict) }
            refreshCache()
            for (id in kept.value) {
                if (id !in kept.value) continue
                val book = all.firstOrNull { it.id == id } ?: continue
                try {
                    val missing = book.files.filterNot(::complete)
                    if (missing.isEmpty()) {
                        setError(id, null)
                        continue
                    }
                    if (url == null) continue
                    val needed = missing.sumOf { it.size - part(it).length().coerceAtMost(it.size) }
                    if (root.usableSpace < needed) throw IOException("Not enough space on the device")
                    limitBytes().takeIf { it > 0 }?.let { limit ->
                        val plan = CachePolicy.plan(cachedBooks(), limit, needed, target = id)
                        if (!plan.fits) throw IOException("Doesn't fit in the storage limit for kept books")
                        evict(plan.evict)
                    }
                    for (f in missing) {
                        if (id !in kept.value) break
                        download(book, f)
                    }
                    setError(id, null)
                } catch (e: IOException) {
                    setError(id, e.message ?: "Download failed")
                }
                refreshCache()
            }
        } finally {
            _active.value = false
        }
    }

    private fun setError(id: String, error: String?) {
        _cache.update { all -> all[id]?.let { all + (id to it.copy(error = error)) } ?: all }
    }

    private fun download(book: RemoteBook, f: RemoteFile) {
        val part = part(f)
        if (part.length() > f.size) part.delete()
        val from = part.length()
        if (from < f.size) {
            val c = open("/v1/files/${f.id}", from)
            try {
                val append = when (c.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        // Only continue a file from exactly where it stopped.
                        if (c.getHeaderField("Content-Range")?.startsWith("bytes $from-") != true) throw IOException("Server sent the wrong part")
                        true
                    }
                    HttpURLConnection.HTTP_OK -> false
                    416 -> {
                        part.delete()
                        throw IOException("File changed on the server")
                    }
                    HttpURLConnection.HTTP_UNAUTHORIZED -> throw SignedOut()
                    else -> throw IOException("Server answered ${c.responseCode}")
                }
                _active.value = true
                var done = if (append) from else 0
                val others = book.files.filter { it !== f }.sumOf { if (complete(it)) it.size else part(it).length().coerceAtMost(it.size) }
                val total = book.files.sumOf { it.size }
                var reported = 0L
                c.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (book.id !in kept.value) return
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (done - reported >= 256 * 1024) {
                                reported = done
                                _cache.update { it + (book.id to CacheState(others + done, total, true)) }
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
        }
        if (part.length() != f.size) throw IOException("Download incomplete")
        if (!part.renameTo(copy(f))) throw IOException("Could not save the file")
        copyPaths[f.id] = f.path
        save()
    }

    private fun fetchCovers() {
        val missing = all.filter { it.id !in hidden }.filter { b -> coverFile(b)?.isFile == false }
        if (missing.isEmpty() || url == null) return
        scope.launch(Dispatchers.IO) {
            var any = false
            for (b in missing) {
                val target = coverFile(b) ?: continue
                val tmp = File(target.path + ".tmp")
                runCatching {
                    val c = open("/v1/covers/${b.coverId}", 0)
                    try {
                        if (c.responseCode != HttpURLConnection.HTTP_OK) return@runCatching
                        c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        if (tmp.renameTo(target)) any = true
                    } finally {
                        c.disconnect()
                    }
                }
                tmp.delete()
            }
            if (any) library.setRemote(visible())
        }
    }

    private fun open(path: String, from: Long): HttpURLConnection {
        val base = url ?: throw IOException("Not connected")
        val key = token ?: throw IOException("Not connected")
        if (!ServerAddress.allowed(base)) throw NotAllowed()
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Authorization", "Bearer $key")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    /** Forgets the server: its books, covers and every copy kept of them. */
    fun clear() {
        val covered = all.mapNotNull(::coverFile)
        kept.value = emptySet()
        all = emptyList()
        hidden = emptySet()
        serverId = null
        copyPaths.clear()
        missingSince.clear()
        index()
        library.setRemote(emptyList())
        _cache.value = emptyMap()
        scope.launch(Dispatchers.IO) {
            root.listFiles()?.forEach { it.delete() }
            covered.forEach { it.delete() }
            file.delete()
        }
    }

    // --- what's kept ---

    @Synchronized
    private fun save() {
        val root = JSONObject()
            .put("serverId", serverId ?: JSONObject.NULL)
            .put("kept", JSONArray(kept.value.toList()))
            .put("hidden", JSONArray(hidden.toList()))
            .put("copyPaths", JSONObject().apply { copyPaths.forEach { (id, path) -> put(id, path) } })
            .put("missingSince", JSONObject().apply { missingSince.forEach { (id, at) -> put(id, at) } })
            .put("books", JSONArray().apply { all.forEach { put(it.json()) } })
        Store.writeAtomically(file, root.toString())
    }

    private fun load() {
        runCatching {
            if (!file.isFile) return
            val o = JSONObject(file.readText())
            fun strings(name: String) = o.getJSONArray(name).let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }
            serverId = if (o.isNull("serverId")) null else o.getString("serverId")
            kept.value = strings("kept")
            hidden = strings("hidden")
            o.optJSONObject("copyPaths")?.let { p -> p.keys().forEach { copyPaths[it] = p.getString(it) } }
            o.optJSONObject("missingSince")?.let { m -> m.keys().forEach { missingSince[it] = m.getLong(it) } }
            val books = o.getJSONArray("books")
            all = (0 until books.length()).map { RemoteBook.of(books.getJSONObject(it)) }
                .filter { b -> b.files.isNotEmpty() && b.files.all { safe(it.id) } }
        }
    }
}
