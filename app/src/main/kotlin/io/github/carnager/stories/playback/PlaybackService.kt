package io.github.carnager.stories.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.carnager.stories.App
import io.github.carnager.stories.MainActivity
import io.github.carnager.stories.data.Book
import io.github.carnager.stories.data.Entry
import io.github.carnager.stories.data.Status
import io.github.carnager.stories.data.status

/**
 * Keeps playing with the app closed, and gives the lock screen, the
 * notification, headphones and cars the app's one player. Its back and
 * forward buttons step through the book rather than from file to file.
 * Cars also browse it: the books being heard, and the folders as they are.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val playback = App.instance.playback
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setDisplayName("Back")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName("Forward")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        session = MediaLibrarySession.Builder(this, playback.player, Library())
            .setSessionActivity(open)
            .setMediaButtonPreferences(listOf(back, forward))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        App.instance.library.flush()
        // The player is the app's and outlives the service; only the session goes.
        session?.release()
        session = null
        super.onDestroy()
    }

    /** What a car sees: two tabs, the books being heard and the folders. */
    private inner class Library : MediaLibrarySession.Callback {
        private val app get() = App.instance

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folderItem(ROOT, "Stories"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val progress = app.library.progress.value
            val children: List<MediaItem> = when {
                parentId == ROOT -> listOf(folderItem(LISTENING, "Listening"), folderItem(FOLDERS, "Folders"))
                parentId == LISTENING -> app.library.books.value
                    .filter { progress[it.id].status() == Status.Listening }
                    .sortedByDescending { progress[it.id]?.lastPlayed ?: 0 }
                    .map(::bookItem)
                parentId == FOLDERS -> entries(app.library.rootFolder())
                parentId.startsWith(FOLDER) -> entries(parentId.removePrefix(FOLDER))
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(children.size)
            val paged = children.subList(from, (from + pageSize).coerceAtMost(children.size))
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(paged), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val book = app.library.book(mediaId.removePrefix(BOOK))
            return Futures.immediateFuture(
                if (book != null) LibraryResult.ofItem(bookItem(book), null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        /** A book chosen in the car: its files, from where it was left. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val id = mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.mediaId
            val book = id?.takeIf { it.startsWith(BOOK) }?.let { app.library.book(it.removePrefix(BOOK)) }
            if (book == null) return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            val (items, at) = app.playback.adopt(book)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, at.first, at.second))
        }

        // Play pressed on headphones with the app gone: the book last heard.
        @Deprecated("Superseded upstream; still called")
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val player = app.playback.exo
            val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
            )
        }

        private fun entries(folder: String?): List<MediaItem> = app.library.entries(folder).map { entry ->
            when (entry) {
                is Entry.Folder -> folderItem(FOLDER + entry.path, entry.name, entry.books.firstOrNull { it.cover != null })
                is Entry.Item -> bookItem(entry.book, entry.name)
            }
        }

        private fun folderItem(id: String, title: String, coverOf: Book? = null) = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                    .setArtworkUri(coverOf?.cover?.let { CoverProvider.uri(this@PlaybackService, it) })
                    .build()
            )
            .build()

        private fun bookItem(book: Book, name: String = book.title): MediaItem {
            val p = app.library.progress.value[book.id]
            val state = when (p.status()) {
                Status.New -> "New"
                Status.Finished -> "Finished"
                Status.Listening -> "${(p!!.fraction * 100).toInt()}%"
            }
            return MediaItem.Builder()
                .setMediaId(BOOK + book.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(name)
                        .setSubtitle(listOf(book.author, state).filter { it.isNotEmpty() }.joinToString(" · "))
                        .setArtist(book.author.ifEmpty { null })
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                        .setArtworkUri(book.cover?.let { CoverProvider.uri(this@PlaybackService, it) })
                        .build()
                )
                .build()
        }
    }

    private companion object {
        const val ROOT = "root"
        const val LISTENING = "listening"
        const val FOLDERS = "folders"
        const val FOLDER = "folder:"
        const val BOOK = "book:"
    }
}
