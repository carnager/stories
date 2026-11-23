package io.github.carnager.stories

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import io.github.carnager.stories.data.Book
import io.github.carnager.stories.data.Deleter
import io.github.carnager.stories.data.DownloadService
import io.github.carnager.stories.data.Library
import io.github.carnager.stories.data.Remote
import io.github.carnager.stories.data.SERVER_ROOT
import io.github.carnager.stories.data.SettingsStore
import io.github.carnager.stories.data.Sync
import io.github.carnager.stories.playback.Playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class App : Application() {
    val scope = MainScope()
    lateinit var settings: SettingsStore
        private set
    lateinit var library: Library
        private set
    lateinit var playback: Playback
        private set
    lateinit var sync: Sync
        private set
    lateinit var remote: Remote
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        library = Library(filesDir, settings, scope)
        remote = Remote(filesDir, library, settings, scope)
        playback = Playback(this, library, settings, scope, remote)
        remote.loadedBook = { playback.state.value.bookId }
        remote.loadedPaths = {
            playback.book?.takeIf { it.onServer }?.tracks?.mapTo(HashSet()) { it.path.removePrefix("$SERVER_ROOT/") }.orEmpty()
        }
        sync = Sync(filesDir, library, playback, remote, scope)
        scope.launch { remote.active.collect { if (it) DownloadService.start(this@App) } }
        scope.launch { settings.state.map { it.cacheLimitGb }.distinctUntilChanged().collect { remote.resumeDownloads() } }
        // The widget follows what plays, and the book's place each time it pauses.
        scope.launch {
            playback.state.map { it.bookId to it.playing }.distinctUntilChanged().collect { (book, playing) ->
                Widget.update(this@App)
                sync.onPlaying(book, playing)
            }
        }
        // Back in reach of the server: what waited goes up.
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = sync.now()
        })
    }

    /**
     * Deletes books from the phone and everything Stories kept of them,
     * letting go of one that's in the player first.
     */
    fun deleteBooks(books: List<Book>, onDone: (Deleter.Result) -> Unit) {
        // Only ever the phone's own files: a server's books are not this app's to delete.
        val local = books.filterNot { it.onServer }
        if (playback.book?.id in local.map { it.id }) playback.unload()
        scope.launch {
            val result = withContext(Dispatchers.IO) { Deleter.delete(local, settings.value.roots) }
            library.forget(local)
            onDone(result)
        }
    }

    /**
     * Stops using the server: its books leave the library and their copies
     * the device. Nothing on the server changes, nor anything of local books.
     */
    fun leaveServer() {
        val books = library.serverBooks()
        if (playback.book?.onServer == true) playback.unload()
        scope.launch {
            // First, or the bookmarks removed below would go up as deleted.
            sync.disconnect()
            library.forget(books)
            remote.clear()
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
