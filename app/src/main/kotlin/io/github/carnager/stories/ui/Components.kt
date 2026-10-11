package io.github.carnager.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.carnager.stories.playback.Playback
import kotlinx.coroutines.delay

/** The screen's name, large, with its actions beside it. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

/** A small capital label over a group of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalTones.current.muted,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
    )
}

/** A rounded, tinted button with an icon and words: the screens' secondary actions. */
@Composable
fun Pill(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = LocalTones.current.raised,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(onClick = onClick)
            .padding(start = if (icon != null) 14.dp else 18.dp, end = 18.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = content)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** A thin bar, filled to a fraction. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, track: Color = LocalTones.current.hairline, height: Dp = 3.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height)).background(color))
    }
}

/**
 * A line to drag along with a round thumb. Material's slider is heavier
 * than the player wants; this one also takes a tap anywhere.
 */
@Composable
fun ThinSlider(
    value: Float,
    fill: Color,
    track: Color,
    thumb: Color,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp,
    enabled: Boolean = true,
) {
    var width by remember { mutableIntStateOf(1) }
    Box(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onChange((offset.x / width).coerceIn(0f, 1f))
                    onDone()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { onChange((it.x / width).coerceIn(0f, 1f)) },
                    onDragEnd = onDone,
                    onDragCancel = onDone,
                ) { change, _ -> onChange((change.position.x / width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(track))
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(height).clip(RoundedCornerShape(height)).background(fill))
        val offset = with(LocalDensity.current) { (value.coerceIn(0f, 1f) * width).toDp() - 8.dp }
        Box(Modifier.offset(x = offset.coerceAtLeast(0.dp)).size(16.dp).clip(CircleShape).background(thumb))
    }
}

/** The book's place, ticking while it plays. */
@Composable
fun livePosition(playback: Playback, playing: Boolean): Long {
    val position by produceState(playback.position(), playing, playback.book?.id) {
        while (true) {
            value = playback.position()
            delay(if (playing) 250 else 1000)
        }
    }
    return position
}

fun clock(ms: Long): String {
    if (ms < 0) return "–:––"
    val s = ms / 1000
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** "4 h 12 min", "38 min": how long, said the way people say it. */
fun span(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        ms in 1 until 60_000 -> "under a minute"
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

fun speedLabel(speed: Float): String {
    val text = "%.2f".format(speed).trimEnd('0').trimEnd('.', ',')
    return "${text}×"
}

/** A book by id that follows the library: renamed, rejoined or rescanned, the screen shows the new one. */
@Composable
fun bookFor(app: io.github.carnager.stories.App, id: String?): io.github.carnager.stories.data.Book? {
    val books by app.library.books.collectAsState()
    return remember(books, id) { app.library.book(id) }
}
