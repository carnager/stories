package io.github.carnager.stories

import android.app.Application
import io.github.carnager.stories.data.Library
import io.github.carnager.stories.data.SettingsStore
import io.github.carnager.stories.playback.Playback
import kotlinx.coroutines.MainScope
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        library = Library(filesDir, settings, scope)
        playback = Playback(this, library, settings, scope)
        // The widget follows what plays, and the book's place each time it pauses.
        scope.launch {
            playback.state.map { it.bookId to it.playing }.distinctUntilChanged().collect { Widget.update(this@App) }
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
