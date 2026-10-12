package io.github.carnager.stories.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import io.github.carnager.stories.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** A running sleep timer: time left while playing, or until the chapter ends. */
data class Sleep(val remainingMs: Long, val totalMs: Long, val endOfChapter: Boolean)

/**
 * Stops playing after a while, fading out over its last stretch, as long
 * as set. It only counts down while playing. A shake starts it over --
 * also for a minute after it stopped, which then plays on: the
 * half-asleep way to say "not yet".
 */
class SleepTimer(
    private val context: Context,
    private val playback: Playback,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : SensorEventListener {
    private val _state = MutableStateFlow<Sleep?>(null)
    val state: StateFlow<Sleep?> = _state.asStateFlow()

    private var job: Job? = null
    private var chapter = -1
    private var expiredAt = 0L
    private var lastTotal = 0L
    private var listening = false

    fun start(minutes: Int) {
        lastTotal = minutes * 60_000L
        _state.value = Sleep(lastTotal, lastTotal, endOfChapter = false)
        run()
    }

    fun endOfChapter() {
        val book = playback.book ?: return
        chapter = book.chapterAt(playback.position())
        lastTotal = 0
        _state.value = Sleep(remainingToChapterEnd(), 0, endOfChapter = true)
        run()
    }

    fun cancel() {
        job?.cancel()
        _state.value = null
        playback.exo.volume = 1f
        expiredAt = 0
        listen(false)
    }

    /** Called as playing starts: a timer that ran out may come back on a shake, not on its own. */
    fun onPlay() {
        playback.exo.volume = 1f
    }

    private fun remainingToChapterEnd(): Long {
        val book = playback.book ?: return 0
        val c = book.chapters.getOrNull(chapter) ?: return 0
        return ((c.startMs + c.durationMs - playback.position()) / playback.exo.playbackParameters.speed).toLong().coerceAtLeast(0)
    }

    private fun run() {
        job?.cancel()
        expiredAt = 0
        listen(settings.value.shakeToExtend)
        job = scope.launch {
            var last = SystemClock.elapsedRealtime()
            while (true) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                val step = now - last
                last = now
                val current = _state.value ?: break
                if (!playback.exo.isPlaying) continue
                val remaining = if (current.endOfChapter) {
                    val book = playback.book
                    // Past the chapter's end: stop at the next one's start.
                    if (book != null && book.chapterAt(playback.position()) != chapter) 0 else remainingToChapterEnd()
                } else current.remainingMs - step
                if (remaining <= 0) {
                    expire()
                    break
                }
                playback.exo.volume = fade(remaining)
                _state.value = current.copy(remainingMs = remaining)
            }
        }
    }

    /**
     * The volume while fading: along a curve, as the ear hears loudness, so
     * it seems to fall evenly rather than all at the end.
     */
    private fun fade(remaining: Long): Float {
        val length = settings.value.sleepFadeSeconds * 1000L
        if (length <= 0 || remaining >= length) return 1f
        val f = remaining.toFloat() / length
        return (f * f).coerceIn(0.02f, 1f)
    }

    private fun expire() {
        playback.pause()
        playback.exo.volume = 1f
        _state.value = null
        expiredAt = SystemClock.elapsedRealtime()
        // Listening on for a minute, for a shake that says "not yet".
        scope.launch {
            delay(GRACE)
            if (_state.value == null) listen(false)
        }
    }

    private fun shaken() {
        val now = SystemClock.elapsedRealtime()
        when {
            _state.value?.endOfChapter == true -> return
            _state.value != null -> {
                _state.value = _state.value!!.copy(remainingMs = lastTotal)
                playback.exo.volume = 1f
                buzz()
            }
            expiredAt > 0 && now - expiredAt < GRACE && lastTotal > 0 -> {
                buzz()
                playback.book?.let { playback.play(it) }
                start((lastTotal / 60_000).toInt())
            }
        }
    }

    private fun buzz() {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
    }

    // --- shake detection ---

    private var peaks = 0
    private var firstPeak = 0L
    private var lastShake = 0L

    private fun listen(on: Boolean) {
        val sensors = context.getSystemService(SensorManager::class.java) ?: return
        if (on && !listening) {
            val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            listening = true
        } else if (!on && listening) {
            sensors.unregisterListener(this)
            listening = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < 2.3f) return
        val now = SystemClock.elapsedRealtime()
        if (now - firstPeak > 1000) {
            firstPeak = now
            peaks = 0
        }
        peaks++
        if (peaks >= 3 && now - lastShake > 1500) {
            lastShake = now
            peaks = 0
            scope.launch { shaken() }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val GRACE = 60_000L
    }
}
