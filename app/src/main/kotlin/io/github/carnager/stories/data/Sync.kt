package io.github.carnager.stories.data

import android.os.SystemClock
import io.github.carnager.stories.playback.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

data class SyncStatus(
    val url: String? = null,
    val syncing: Boolean = false,
    val lastSync: Long = 0,
    val error: String? = null,
)

/**
 * Keeps places and bookmarks in step with a storiesd server. Off, and without
 * any network traffic, until signed in. The server decides which place is
 * newest: local changes go up first, then what it holds comes down.
 *
 * Starting to play is the exception. The place on the phone may be old, so
 * nothing goes up until the server has been asked, and a newer place from
 * another device is followed even though the book has begun.
 */
class Sync(
    dir: File,
    private val library: Library,
    private val playback: Playback,
    private val remote: Remote,
    private val scope: CoroutineScope,
) {
    private val file = File(dir, "sync.json")
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var state: State? = load()

    private val _status = MutableStateFlow(SyncStatus(url = state?.url))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var etag: String? = null
    private var matchedLocal: List<Book>? = null
    private var match = FileMatch(emptyList(), emptyList())
    private var tracks: Map<String, Pair<Book, Int>> = emptyMap()

    /** A newer place from another device, for the book in the player, waiting for an answer. */
    private class Offer(val place: Progress, val record: Sent)
    private var offer: Offer? = null
    private val _newer = MutableStateFlow<Progress?>(null)
    val newer: StateFlow<Progress?> = _newer.asStateFlow()

    // Told to stay here: this place goes up as the newest, whatever else is there.
    private val keepOurs = HashSet<String>()

    private fun setOffer(o: Offer?) {
        offer = o
        _newer.value = o?.place
    }

    /** Goes to the other device's place. */
    suspend fun acceptNewer() = lock.withLock {
        val o = offer ?: return@withLock
        state?.sent?.put(o.place.bookId, o.record)
        setOffer(null)
        library.adoptRemote(listOf(o.place))
        val now = playback.state.value
        if (now.playing && now.bookId == o.place.bookId) playback.follow(o.place.bookId)
        withContext(Dispatchers.IO) { save() }
    }

    /** Stays here; the other device's place is replaced by this one. */
    suspend fun declineNewer() = lock.withLock {
        val o = offer ?: return@withLock
        keepOurs += o.place.bookId
        setOffer(null)
        wake.trySend(Unit)
        Unit
    }

    // Just began to play: up to when nothing is sent, and how recent the phone's own place was.
    @Volatile private var holdUntil = 0L
    @Volatile private var startedBook: String? = null
    @Volatile private var startedPlayed = 0L

    init {
        state?.let { remote.signIn(it.url, it.token, it.serverId) }
        scope.launch { for (ask in wake) cycle() }
        scope.launch {
            while (true) {
                delay(PLAYING_INTERVAL)
                if (playback.state.value.playing) now()
            }
        }
    }

    /** Asks for a sync soon; requests made meanwhile count as one. */
    fun now() {
        if (state != null) wake.trySend(Unit)
    }

    /** The player started or stopped. */
    fun onPlaying(bookId: String?, playing: Boolean) {
        if (state == null) return
        if (playing && bookId != null) {
            startedBook = bookId
            startedPlayed = library.progress.value[bookId]?.lastPlayed ?: 0
            holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
        }
        now()
    }

    /** Signs in. Throws what went wrong; the password is not kept. */
    suspend fun connect(url: String, password: String, device: String) = lock.withLock {
        val login = withContext(Dispatchers.IO) { SyncApi(url).login(password, device) }
        val old = state?.takeIf { it.serverId == login.serverId }
        // Another server knows nothing of what was sent to this one.
        state = (old ?: State(serverId = login.serverId)).copy(url = url, token = login.token, deviceId = login.deviceId)
        etag = null
        matchedLocal = null
        // Copies from a different server are not this one's books.
        if (remote.serverId != null && remote.serverId != login.serverId) remote.clear()
        remote.signIn(url, login.token, login.serverId)
        withContext(Dispatchers.IO) { save() }
        _status.value = SyncStatus(url = url)
        wake.trySend(Unit)
        Unit
    }

    /** Stops syncing. What's on the phone stays. */
    suspend fun disconnect() = lock.withLock { forget(null) }

    private suspend fun forget(error: String?) {
        state = null
        etag = null
        matchedLocal = null
        setOffer(null)
        keepOurs.clear()
        remote.signOut()
        withContext(Dispatchers.IO) { file.delete() }
        _status.value = SyncStatus(error = error)
    }

    private suspend fun cycle() = lock.withLock {
        val s = state ?: return@withLock
        _status.value = _status.value.copy(syncing = true)
        try {
            val api = SyncApi(s.url, s.token)
            val hold = SystemClock.elapsedRealtime() < holdUntil
            val offset = withContext(Dispatchers.IO) {
                val before = System.currentTimeMillis()
                val serverNow = api.serverNow()
                val offset = serverNow - (before + System.currentTimeMillis()) / 2
                refreshMatch(api)
                if (!hold) {
                    pushPositions(api, s)
                    pushBookmarks(api, s)
                }
                pull(api, s)
                offset
            }
            applyPositions(s, offset, hold)
            if (!hold) applyBookmarks(s)
            withContext(Dispatchers.IO) { save() }
            _status.value = _status.value.copy(syncing = false, lastSync = System.currentTimeMillis(), error = null)
            remote.resumeDownloads()
            if (hold) {
                // Asked: what was held back can go up now.
                holdUntil = 0
                wake.trySend(Unit)
            }
        } catch (_: SignedOut) {
            forget("Signed out by the server. Connect again to go on syncing.")
        } catch (e: IOException) {
            _status.value = _status.value.copy(syncing = false, error = e.message ?: "Server not reachable")
        } catch (_: JSONException) {
            _status.value = _status.value.copy(syncing = false, error = "Unexpected answer from the server")
        }
    }

    private fun refreshMatch(api: SyncApi) {
        val changed = api.library(etag)
        if (changed != null) {
            etag = changed.first
            remote.setBooks(changed.second)
            matchedLocal = null
        }
        val local = library.localBooks()
        if (matchedLocal !== local) {
            val roots = library.roots().map { it.trimEnd('/') }
            val files = ArrayList<LocalFile>()
            for (book in local) {
                for (track in book.tracks) {
                    val root = roots.filter { track.path.startsWith("$it/") }.maxByOrNull { it.length }
                    val relative = if (root != null) track.path.removePrefix("$root/") else track.path.substringAfterLast('/')
                    files += LocalFile(track.path, File(track.path).length(), relative)
                }
            }
            match = FileMatch(remote.files(), files)
            remote.publish(match)
            matchedLocal = local
        }
        val where = HashMap<String, Pair<Book, Int>>()
        for (book in library.books.value) {
            book.tracks.forEachIndexed { i, track -> where.putIfAbsent(track.path, book to i) }
        }
        tracks = where
    }

    /** The server's file for a track: a server book's own, or a local file matched to it. */
    private fun serverOf(path: String): ServerFile? = remote.serverFile(path) ?: match.server(path)

    // --- places ---

    private fun recordOf(book: Book, p: Progress): Sent? {
        val index = if (p.finished) book.tracks.lastIndex else p.trackIndex
        val track = book.tracks.getOrNull(index) ?: return null
        val server = serverOf(track.path) ?: return null
        val ms = if (p.finished) track.durationMs else p.trackPositionMs
        return Sent(server.path, server.size, ms, p.finished, hundredths(p.speed.toDouble()), p.lastPlayed)
    }

    private fun pushPositions(api: SyncApi, s: State) {
        val now = System.currentTimeMillis()
        val out = ArrayList<RemotePosition>()
        val marks = HashMap<String, Sent>()
        for (book in library.books.value) {
            // Waiting for an answer: the other device's place isn't overwritten meanwhile.
            if (book.id == offer?.place?.bookId) continue
            val p = library.progress.value[book.id] ?: continue
            val record = recordOf(book, p) ?: continue
            val old = s.sent[book.id]
            val forced = book.id in keepOurs
            if (!forced && old != null && old.same(record)) continue
            // Never heard, never sent: nothing to say.
            if (!forced && old == null && p.lastPlayed <= 0) continue
            // Changed without being played, as a book marked finished or moved while paused: that's new now.
            val at = if (forced || old != null && p.lastPlayed <= old.played || p.lastPlayed <= 0) now else p.lastPlayed
            out += RemotePosition(record.path, record.size, record.ms, record.finished, record.speed, at)
            marks[book.id] = record
        }
        if (out.isEmpty()) return
        api.putPositions(System.currentTimeMillis(), out)
        s.sent.putAll(marks)
        keepOurs.removeAll(marks.keys)
    }

    private fun pull(api: SyncApi, s: State) {
        val (posRev, positions) = api.positions(s.positionsRev)
        positions.forEach { s.remote[it.path to it.size] = it }
        s.positionsRev = posRev
        val (bmRev, bookmarks) = api.bookmarks(s.bookmarksRev)
        bookmarks.forEach { s.remoteBookmarks[it.id] = it }
        s.bookmarksRev = bmRev
    }

    /** A book's place is the newest record among its files. */
    private fun applyPositions(s: State, offset: Long, held: Boolean) {
        val adopted = ArrayList<Progress>()
        var follow: String? = null
        val state = playback.state.value
        val now = System.currentTimeMillis()
        // The question lapses once another book is in the player.
        offer?.let { if (it.place.bookId != state.bookId) setOffer(null) }
        for (book in library.books.value) {
            if (book.id == offer?.place?.bookId || book.id in keepOurs) continue
            val newest = book.tracks.mapNotNull { t -> serverOf(t.path)?.let { s.remote[it.path to it.size] } }
                .maxByOrNull { it.lastPlayed } ?: continue
            val record = Sent(newest.path, newest.size, newest.ms, newest.finished, hundredths(newest.speed), newest.lastPlayed)
            val known = s.sent[book.id]
            if (known != null && known.same(record)) continue
            val at = (newest.lastPlayed - offset).coerceAtMost(now)
            val index = book.tracks.indexOfFirst { serverOf(it.path)?.path == newest.path }
            if (index < 0) continue
            val reset = !newest.finished && newest.ms == 0L && index == 0
            val place = Progress(
                bookId = book.id, fingerprint = book.fingerprint, trackIndex = index, trackPositionMs = newest.ms,
                positionMs = if (newest.finished) book.durationMs else (book.trackStarts[index] + newest.ms).coerceAtMost(book.durationMs),
                durationMs = book.durationMs, finished = newest.finished, speed = newest.speed.toFloat(),
                // Back to the start elsewhere: new here too, not the last book heard.
                lastPlayed = if (reset) 0 else at,
            )
            val agreed = record.copy(played = place.lastPlayed)
            val playing = state.playing && state.bookId == book.id
            if (playing) {
                if (!held) {
                    // Well under way here, and newer elsewhere: the listener decides.
                    setOffer(Offer(place, agreed))
                    continue
                }
                // Only just begun, and the other device was there later: go on from its place.
                if (startedBook != book.id || at <= startedPlayed) continue
                follow = book.id
            } else if (held) {
                // Nothing has gone up yet, so the phone's own place may be the newer.
                val local = library.progress.value[book.id]?.lastPlayed ?: 0
                if (at <= local) continue
            }
            adopted += place
            s.sent[book.id] = agreed
        }
        if (adopted.isEmpty()) return
        library.adoptRemote(adopted)
        follow?.let(playback::follow)
    }

    // --- bookmarks ---

    private fun pushBookmarks(api: SyncApi, s: State) {
        val now = System.currentTimeMillis()
        val local = library.bookmarks.value
        val byLocal = s.bookmarks.entries.associateBy { it.value.localId }
        val out = ArrayList<RemoteBookmark>()
        val known = HashMap(s.bookmarks)
        for (b in local) {
            val entry = byLocal[b.id]
            if (entry != null) {
                if (entry.value.note == b.note) continue
                val k = entry.value
                out += RemoteBookmark(entry.key, k.path, k.size, k.ms, b.note, k.created, now, false)
                known[entry.key] = k.copy(note = b.note)
                continue
            }
            val book = library.book(b.bookId) ?: continue
            val (index, offset) = book.locate(b.positionMs)
            val server = book.tracks.getOrNull(index)?.let { serverOf(it.path) } ?: continue
            val id = "${s.deviceId}-${b.id}"
            out += RemoteBookmark(id, server.path, server.size, offset, b.note, b.created, now, false)
            known[id] = KnownBookmark(b.id, server.path, server.size, offset, b.note, b.created)
        }
        val present = local.mapTo(HashSet()) { it.id }
        for ((id, k) in s.bookmarks) {
            if (k.localId in present) continue
            out += RemoteBookmark(id, k.path, k.size, k.ms, k.note, k.created, now, true)
            known.remove(id)
        }
        if (out.isEmpty()) return
        api.putBookmarks(System.currentTimeMillis(), out)
        s.bookmarks.clear()
        s.bookmarks.putAll(known)
    }

    private fun applyBookmarks(s: State) {
        for (r in s.remoteBookmarks.values.sortedBy { it.created }) {
            val k = s.bookmarks[r.id]
            if (r.deleted) {
                if (k != null) {
                    library.removeBookmark(k.localId)
                    s.bookmarks.remove(r.id)
                }
                continue
            }
            if (k != null) {
                if (k.note != r.note) {
                    library.noteBookmark(k.localId, r.note)
                    s.bookmarks[r.id] = k.copy(note = r.note.trim())
                }
                continue
            }
            // A book not on the phone: kept, for when it comes.
            val file = ServerFile(r.path, r.size)
            val path = match.local(file) ?: remote.trackPath(file) ?: continue
            val (book, index) = tracks[path] ?: continue
            val at = book.trackStarts[index] + r.ms
            val id = library.addSyncedBookmark(book.id, at, r.note, r.created)
            s.bookmarks[r.id] = KnownBookmark(id, r.path, r.size, r.ms, r.note.trim(), r.created)
        }
    }

    // --- what's kept ---

    /** A place as last agreed with the server. [played] is the phone's time for it. */
    private data class Sent(val path: String, val size: Long, val ms: Long, val finished: Boolean, val speed: Double, val played: Long) {
        fun same(o: Sent) = path == o.path && size == o.size && ms == o.ms && finished == o.finished && speed == o.speed
    }

    private data class KnownBookmark(val localId: Long, val path: String, val size: Long, val ms: Long, val note: String, val created: Long)

    private data class State(
        val url: String = "",
        val token: String = "",
        val deviceId: String = "",
        val serverId: String = "",
        var positionsRev: Long = 0,
        var bookmarksRev: Long = 0,
        val sent: HashMap<String, Sent> = HashMap(),
        val remote: HashMap<Pair<String, Long>, RemotePosition> = HashMap(),
        val bookmarks: HashMap<String, KnownBookmark> = HashMap(),
        val remoteBookmarks: HashMap<String, RemoteBookmark> = HashMap(),
    )

    private fun save() {
        val s = state ?: return
        val root = JSONObject()
            .put("url", s.url).put("token", s.token).put("deviceId", s.deviceId).put("serverId", s.serverId)
            .put("positionsRev", s.positionsRev).put("bookmarksRev", s.bookmarksRev)
            .put("sent", JSONObject().apply {
                s.sent.forEach { (id, x) ->
                    put(id, JSONObject().put("path", x.path).put("size", x.size).put("ms", x.ms)
                        .put("finished", x.finished).put("speed", x.speed).put("played", x.played))
                }
            })
            .put("remote", JSONArray().apply { s.remote.values.forEach { put(it.json()) } })
            .put("bookmarks", JSONObject().apply {
                s.bookmarks.forEach { (id, k) ->
                    put(id, JSONObject().put("localId", k.localId).put("path", k.path).put("size", k.size)
                        .put("ms", k.ms).put("note", k.note).put("created", k.created))
                }
            })
            .put("remoteBookmarks", JSONArray().apply { s.remoteBookmarks.values.forEach { put(it.json()) } })
        Store.writeAtomically(file, root.toString())
    }

    private fun load(): State? = runCatching {
        if (!file.isFile) return null
        val o = JSONObject(file.readText())
        val s = State(
            url = o.getString("url"), token = o.getString("token"), deviceId = o.getString("deviceId"),
            serverId = o.getString("serverId"), positionsRev = o.getLong("positionsRev"), bookmarksRev = o.getLong("bookmarksRev"),
        )
        o.getJSONObject("sent").let { sent ->
            sent.keys().forEach { id ->
                val x = sent.getJSONObject(id)
                s.sent[id] = Sent(x.getString("path"), x.getLong("size"), x.getLong("ms"), x.getBoolean("finished"), x.getDouble("speed"), x.getLong("played"))
            }
        }
        o.getJSONArray("remote").let { a ->
            for (i in 0 until a.length()) RemotePosition.of(a.getJSONObject(i)).let { s.remote[it.path to it.size] = it }
        }
        o.getJSONObject("bookmarks").let { known ->
            known.keys().forEach { id ->
                val k = known.getJSONObject(id)
                s.bookmarks[id] = KnownBookmark(k.getLong("localId"), k.getString("path"), k.getLong("size"), k.getLong("ms"), k.getString("note"), k.getLong("created"))
            }
        }
        o.getJSONArray("remoteBookmarks").let { a ->
            for (i in 0 until a.length()) RemoteBookmark.of(a.getJSONObject(i)).let { s.remoteBookmarks[it.id] = it }
        }
        s
    }.getOrNull()

    private fun hundredths(speed: Double) = (speed * 100).roundToInt() / 100.0

    private companion object {
        const val PLAYING_INTERVAL = 30_000L
        const val HOLD_MS = 6_000L
    }
}
