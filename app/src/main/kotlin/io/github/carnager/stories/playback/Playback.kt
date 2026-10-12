package io.github.carnager.stories.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.carnager.stories.data.Book
import io.github.carnager.stories.data.Library
import io.github.carnager.stories.data.Progress
import io.github.carnager.stories.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class PlayState(
    val bookId: String? = null,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val error: String? = null,
)

/**
 * The one player, and everything about it that is a book's rather than a
 * file's: the book's clock across its files, chapters, its own speed, the
 * rewind on resuming, and keeping what's heard. The service shares this
 * player with the lock screen, the notification and headphones.
 */
class Playback(
    private val context: Context,
    private val library: Library,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    /** What the session hands out: skips are seconds, not files. */
    val player: Player = BookPlayer(exo)

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()

    val sleep = SleepTimer(context, this, settings, scope)

    var book: Book? = null
        private set

    private var pausedAt = 0L
    private var ended = false
    // Moved to a chosen place while paused: playing starts there, not before.
    private var sought = false
    private var controller: MediaController? = null

    init {
        exo.skipSilenceEnabled = settings.value.skipSilence
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    pausedAt = SystemClock.elapsedRealtime()
                    save(persist = true)
                }
                _state.update { it.copy(playing = isPlaying, error = if (isPlaying) null else it.error) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && book != null) {
                    ended = true
                    save(persist = true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(playing = false, error = error.localizedMessage ?: error.errorCodeName) }
            }
        })
        scope.launch {
            var ticks = 0
            while (true) {
                delay(1000)
                if (exo.isPlaying) save(persist = ++ticks % 10 == 0)
            }
        }
        scope.launch { settings.state.collect { exo.skipSilenceEnabled = it.skipSilence } }
        // A paused book whose place changed elsewhere goes there, or its next save would undo the change.
        scope.launch {
            library.replaced.collect { ids ->
                val b = book ?: return@collect
                if (b.id !in ids || exo.isPlaying) return@collect
                val p = library.progress.value[b.id] ?: return@collect
                ended = p.finished
                val (index, offset) = b.locate(if (p.finished) b.durationMs else p.positionMs)
                exo.seekTo(index, offset)
                exo.setPlaybackSpeed(p.speed)
                _state.update { it.copy(speed = p.speed) }
                sought = true
            }
        }
        // The book playing joined with others, or cut apart: on in its new shape, from the same spot.
        scope.launch {
            library.books.collect {
                val b = book ?: return@collect
                if (library.book(b.id) != null) return@collect
                val path = b.tracks.getOrNull(exo.currentMediaItemIndex)?.path ?: return@collect
                val now = it.firstOrNull { n -> n.tracks.any { t -> t.path == path } } ?: return@collect
                val fileIndex = now.tracks.indexOfFirst { t -> t.path == path }
                val at = now.trackStarts[fileIndex] + exo.currentPosition
                val playing = exo.isPlaying
                load(now, at)
                if (playing) exo.play()
            }
        }
        restoreLast()
    }

    /** The service, started by connecting to it: then it can stay in front while playing. */
    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, context.mainExecutor)
    }

    /** The book last heard, ready to go on where it stopped, without playing. */
    private fun restoreLast() {
        val last = library.progress.value.values.filter { !it.finished && it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed } ?: return
        val book = library.book(last.bookId) ?: return
        load(book, last.positionMs)
    }

    /** A book's files as the player's items, each carrying the book's name and cover. */
    fun itemsOf(book: Book): List<MediaItem> {
        val cover = book.cover?.let { CoverProvider.uri(context, it) }
        return book.tracks.mapIndexed { i, track ->
            MediaItem.Builder()
                .setMediaId("${book.id}/$i")
                .setUri(Uri.fromFile(File(track.path)))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.title)
                        .setArtist(book.author.ifEmpty { null })
                        .setAlbumTitle(book.title)
                        .setArtworkUri(cover)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                        .build()
                )
                .build()
        }
    }

    /**
     * Takes up a book whose items someone else -- a car, a watch -- is about
     * to give the player: where to start, at its speed.
     */
    fun adopt(book: Book): Pair<List<MediaItem>, Pair<Int, Long>> {
        save(persist = true)
        val p = library.progress.value[book.id]
        this.book = book
        ended = false
        pausedAt = 0
        sought = true
        val speed = p?.speed ?: 1f
        exo.setPlaybackSpeed(speed)
        _state.update { it.copy(bookId = book.id, speed = speed, error = null) }
        return itemsOf(book) to book.locate(if (p == null || p.finished) 0 else p.positionMs)
    }

    private fun load(book: Book, atMs: Long) {
        this.book = book
        ended = false
        val (index, offset) = book.locate(atMs)
        exo.setMediaItems(itemsOf(book), index, offset)
        exo.prepare()
        val speed = library.progress.value[book.id]?.speed ?: 1f
        exo.setPlaybackSpeed(speed)
        pausedAt = 0
        sought = false
        _state.update { it.copy(bookId = book.id, speed = speed, error = null) }
    }

    /** Plays a book from where it was left, or from the start once finished. */
    fun play(book: Book, atMs: Long? = null) {
        if (this.book?.id != book.id || exo.playbackState == Player.STATE_IDLE || exo.mediaItemCount == 0) {
            save(persist = true)
            val p = library.progress.value[book.id]
            load(book, atMs ?: if (p == null || p.finished) 0 else p.positionMs)
            if (atMs != null) sought = true
        } else if (atMs != null) {
            seekTo(atMs)
        } else if (exo.playbackState == Player.STATE_ENDED || ended || library.progress.value[book.id]?.finished == true) {
            seekTo(0)
        }
        connect()
        player.play()
    }

    /** Lets go of the book: stopped, emptied, nothing kept -- it is being deleted. */
    fun unload() {
        sleep.cancel()
        exo.stop()
        exo.clearMediaItems()
        book = null
        ended = false
        _state.value = PlayState()
    }

    fun toggle() {
        if (exo.isPlaying) player.pause() else book?.let { play(it) }
    }

    fun pause() = player.pause()

    /** Where the book is, on its own clock. */
    fun position(): Long {
        val b = book ?: return 0
        val i = exo.currentMediaItemIndex
        if (i !in b.tracks.indices) return 0
        return b.trackStarts[i] + exo.currentPosition.coerceAtLeast(0)
    }

    fun seekTo(bookMs: Long) {
        val b = book ?: return
        val (index, offset) = b.locate(bookMs)
        ended = false
        if (!exo.isPlaying) sought = true
        exo.seekTo(index, offset)
        save(persist = false)
    }

    fun skip(deltaMs: Long) {
        val b = book ?: return
        seekTo((position() + deltaMs).coerceIn(0, (b.durationMs - 1000).coerceAtLeast(0)))
    }

    fun rewind() = skip(-settings.value.rewindSeconds * 1000L)
    fun forward() = skip(settings.value.forwardSeconds * 1000L)

    /** The outer buttons: a chapter, or as many seconds as set. */
    fun outerBack() = settings.value.outerSkipSeconds.let { if (it > 0) skip(-it * 1000L) else previousChapter() }
    fun outerForward() = settings.value.outerSkipSeconds.let { if (it > 0) skip(it * 1000L) else nextChapter() }

    fun nextChapter() {
        val b = book ?: return
        val next = b.chapters.getOrNull(b.chapterAt(position()) + 1) ?: return
        seekTo(next.startMs)
    }

    /** Back to the chapter's start, or the one before when just past it. */
    fun previousChapter() {
        val b = book ?: return
        val at = b.chapterAt(position())
        val chapter = b.chapters.getOrNull(at) ?: return
        val target = if (position() - chapter.startMs > 4000 || at == 0) chapter.startMs else b.chapters[at - 1].startMs
        seekTo(target)
    }

    fun setSpeed(speed: Float) {
        val s = (Math.round(speed * 20) / 20f).coerceIn(0.5f, 3.5f)
        exo.setPlaybackSpeed(s)
        _state.update { it.copy(speed = s) }
        save(persist = true)
    }

    /**
     * Back a little after a pause, so the sentence that was cut makes sense:
     * more the longer it was.
     */
    private fun smartRewind() {
        if (sought) { sought = false; return }
        if (!settings.value.smartRewind || book == null) return
        val since = if (pausedAt > 0) SystemClock.elapsedRealtime() - pausedAt
        else System.currentTimeMillis() - (library.progress.value[book?.id]?.lastPlayed ?: return)
        val back = when {
            since < 5_000 -> 0L
            since < 60_000 -> 3_000L
            since < 15 * 60_000 -> 10_000L
            since < 3 * 3600_000 -> 20_000L
            else -> 30_000L
        }
        if (back > 0) skip(-back)
        sought = false
    }

    fun save(persist: Boolean) {
        val b = book ?: return
        val i = exo.currentMediaItemIndex.coerceIn(0, b.tracks.lastIndex.coerceAtLeast(0))
        val old = library.progress.value[b.id]
        library.setProgress(
            Progress(
                bookId = b.id,
                fingerprint = b.fingerprint,
                trackIndex = i,
                trackPositionMs = exo.currentPosition.coerceAtLeast(0),
                positionMs = if (ended) b.durationMs else position(),
                durationMs = b.durationMs,
                finished = ended,
                speed = exo.playbackParameters.speed,
                lastPlayed = if (exo.isPlaying || ended) System.currentTimeMillis() else old?.lastPlayed ?: System.currentTimeMillis(),
            ),
            persist,
            shared = persist && !exo.isPlaying,
        )
    }

    private inner class BookPlayer(player: Player) : ForwardingPlayer(player) {
        override fun play() {
            if (!exo.isPlaying) {
                if (exo.playbackState == Player.STATE_ENDED) seekTo(0) else smartRewind()
                ended = false
            }
            sleep.onPlay()
            super.play()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) play() else super.setPlayWhenReady(false)
        }

        override fun seekBack() = rewind()
        override fun seekForward() = forward()
        override fun getSeekBackIncrement() = settings.value.rewindSeconds * 1000L
        override fun getSeekForwardIncrement() = settings.value.forwardSeconds * 1000L

        // Headphones' and cars' next and previous: a step through the book.
        override fun seekToNext() = forward()
        override fun seekToPrevious() = rewind()
        override fun seekToNextMediaItem() = forward()
        override fun seekToPreviousMediaItem() = rewind()
        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun isCommandAvailable(command: Int) = when (command) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD -> book != null
            else -> super.isCommandAvailable(command)
        }

        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .addAll(COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD, COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS)
            .build()
    }
}
