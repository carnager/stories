package io.github.carnager.stories.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val roots: List<String> = emptyList(),
    val rewindSeconds: Int = 15,
    val forwardSeconds: Int = 30,
    val smartRewind: Boolean = true,
    val skipSilence: Boolean = false,
    val shakeToExtend: Boolean = true,
    val sleepMinutes: Int = 30,
    val theme: String = "system",
    val dynamicColor: Boolean = false,
    val grid: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    private fun read() = Settings(
        roots = prefs.getString("roots", "")!!.split('\n').filter { it.isNotBlank() },
        rewindSeconds = prefs.getInt("rewind", 15),
        forwardSeconds = prefs.getInt("forward", 30),
        smartRewind = prefs.getBoolean("smartRewind", true),
        skipSilence = prefs.getBoolean("skipSilence", false),
        shakeToExtend = prefs.getBoolean("shake", true),
        sleepMinutes = prefs.getInt("sleep", 30),
        theme = prefs.getString("theme", "system")!!,
        dynamicColor = prefs.getBoolean("dynamic", false),
        grid = prefs.getBoolean("grid", true),
    )

    fun update(change: (Settings) -> Settings) {
        val s = change(_state.value)
        prefs.edit()
            .putString("roots", s.roots.joinToString("\n"))
            .putInt("rewind", s.rewindSeconds)
            .putInt("forward", s.forwardSeconds)
            .putBoolean("smartRewind", s.smartRewind)
            .putBoolean("skipSilence", s.skipSilence)
            .putBoolean("shake", s.shakeToExtend)
            .putInt("sleep", s.sleepMinutes)
            .putString("theme", s.theme)
            .putBoolean("dynamic", s.dynamicColor)
            .putBoolean("grid", s.grid)
            .apply()
        _state.value = s
    }
}
