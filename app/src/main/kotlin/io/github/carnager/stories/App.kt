package io.github.carnager.stories

import android.app.Application
import io.github.carnager.stories.data.Library
import io.github.carnager.stories.data.SettingsStore
import io.github.carnager.stories.playback.Playback
import kotlinx.coroutines.MainScope

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
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
