package io.github.carnager.stories.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import io.github.carnager.stories.App
import io.github.carnager.stories.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The player's colours, from the cover: a deep ground and a light accent. */
data class Ground(val top: Color, val bottom: Color, val accent: Color, val text: Color, val soft: Color)

private val grounds = HashMap<String, Ground>()

@Composable
private fun groundFor(book: Book?): Ground {
    // No picture: the made-up cover's colour, so the player is as dark as with one.
    val madeUp = book?.takeIf { it.cover == null }?.let { madeCoverColours(it.title) }
    if (madeUp != null) {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(madeUp.first.toArgb(), it) }
        val text = Color(0xFFF6F1E9)
        return Ground(
            top = Color(ColorUtils.HSLToColor(hsl.copyOf().also { it[2] = 0.22f })),
            bottom = Color(ColorUtils.HSLToColor(hsl.copyOf().also { it[2] = 0.08f })),
            accent = madeUp.second,
            text = text,
            soft = text.copy(alpha = 0.68f),
        )
    }
    val fallback = Ground(
        top = MaterialTheme.colorScheme.surfaceContainerHigh,
        bottom = MaterialTheme.colorScheme.surface,
        accent = MaterialTheme.colorScheme.primary,
        text = MaterialTheme.colorScheme.onSurface,
        soft = LocalTones.current.secondary,
    )
    val key = book?.cover
    val made by produceState(key?.let { grounds[it] } ?: fallback, key) {
        val path = key ?: return@produceState
        grounds[path]?.let { value = it; return@produceState }
        val bitmap = CoverCache.load(path, 160) ?: return@produceState
        val ground = withContext(Dispatchers.Default) { fromPalette(Palette.from(bitmap.asAndroidBitmap()).generate()) } ?: return@produceState
        grounds[path] = ground
        value = ground
    }
    return made
}

private fun fromPalette(palette: Palette): Ground? {
    val base = palette.darkMutedSwatch ?: palette.mutedSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch ?: return null
    val light = palette.lightVibrantSwatch ?: palette.lightMutedSwatch ?: palette.vibrantSwatch ?: palette.mutedSwatch
    fun hsl(rgb: Int) = FloatArray(3).also { ColorUtils.colorToHSL(rgb, it) }
    val top = hsl(base.rgb).also { it[1] = it[1].coerceAtMost(0.4f); it[2] = 0.22f }
    val bottom = hsl(base.rgb).also { it[1] = it[1].coerceAtMost(0.3f); it[2] = 0.08f }
    val accent = hsl(light?.rgb ?: base.rgb).also { it[1] = it[1].coerceIn(0.25f, 0.6f); it[2] = it[2].coerceIn(0.68f, 0.8f) }
    val text = Color(0xFFF6F1E9)
    return Ground(
        top = Color(ColorUtils.HSLToColor(top)),
        bottom = Color(ColorUtils.HSLToColor(bottom)),
        accent = Color(ColorUtils.HSLToColor(accent)),
        text = text,
        soft = text.copy(alpha = 0.68f),
    )
}

/**
 * The book playing, full screen, on its cover's colours. The chapter is
 * what the bar runs along -- a book's whole length is too long to aim in --
 * with the book's progress beneath it. Pull down to put it away.
 */
@Composable
fun PlayerScreen(app: App, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    LightBars()
    val state by app.playback.state.collectAsState()
    val sleep by app.playback.sleep.state.collectAsState()
    val settings by app.settings.state.collectAsState()
    val book = bookFor(app, state.bookId)
    val ground = groundFor(book)
    val top by animateColorAsState(ground.top, tween(600), label = "top")
    val bottom by animateColorAsState(ground.bottom, tween(600), label = "bottom")
    val accent by animateColorAsState(ground.accent, tween(600), label = "accent")
    val text = ground.text
    var sheet by remember { mutableStateOf<String?>(null) }
    var marked by remember { mutableStateOf(0L) }
    var pull by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    Surface(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, pull.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (pull > with(density) { 140.dp.toPx() }) onClose(); pull = 0f },
                    onDragCancel = { pull = 0f },
                ) { _, dy -> pull = (pull + dy).coerceAtLeast(0f) }
            },
        color = bottom,
        contentColor = text,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
            if (book == null) {
                Text("Nothing playing", Modifier.align(Alignment.Center), color = ground.soft)
                return@Box
            }
            val position = livePosition(app.playback, state.playing)
            val chapterIndex = book.chapterAt(position)
            val chapter = book.chapters.getOrNull(chapterIndex)
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, modifier = Modifier.offset(x = (-12).dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(30.dp))
                    }
                    Text(
                        (book.series.ifEmpty { book.author }).uppercase(),
                        style = MaterialTheme.typography.labelSmall, color = ground.soft,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { sheet = "chapters" }, modifier = Modifier.offset(x = 12.dp)) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Chapters")
                    }
                }
                Spacer(Modifier.weight(0.3f))
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth, 380.dp)
                    // The cover breathes a little: full while playing, a touch smaller paused.
                    val scale by androidx.compose.animation.core.animateFloatAsState(if (state.playing) 1f else 0.92f, tween(450), label = "scale")
                    // Lit along its edge as far as the whole book is heard.
                    ProgressEdge(
                        position.toFloat() / book.durationMs.coerceAtLeast(1),
                        accent,
                        Modifier.width(side).graphicsLayer { scaleX = scale; scaleY = scale },
                        corner = 18.dp,
                        width = 3.dp,
                        gap = 7.dp,
                    ) {
                        Box(Modifier.shadow(30.dp, RoundedCornerShape(18.dp))) {
                            Cover(book, Modifier.fillMaxWidth(), corner = 18.dp)
                        }
                    }
                }
                Spacer(Modifier.weight(0.25f))
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        chapter?.title?.ifBlank { null } ?: book.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (chapter?.title.isNullOrBlank() || chapter?.title == book.title) book.author else book.title,
                        style = MaterialTheme.typography.bodyMedium, color = ground.soft,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(18.dp))
                ChapterBar(app, book, position, chapterIndex, accent, text, ground.soft)
                Spacer(Modifier.height(6.dp))
                Transport(app, state.playing, settings.rewindSeconds, settings.forwardSeconds, settings.outerSkipSeconds, accent, text, bottom)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Chip(Icons.Default.Speed, speedLabel(state.speed), state.speed != 1f, accent, ground.soft) { sheet = "speed" }
                    Chip(Icons.Default.Bedtime, sleep?.let { if (it.endOfChapter) "Chapter end" else clock(it.remainingMs) } ?: "Sleep",
                        sleep != null, accent, ground.soft) { sheet = "sleep" }
                    Chip(Icons.Default.BookmarkAdd, "Mark", false, accent, ground.soft) {
                        marked = app.library.addBookmark(book.id, app.playback.position(), "")
                        sheet = "marked"
                    }
                }
                Spacer(Modifier.weight(0.2f))
            }
        }
    }

    when (sheet) {
        "chapters" -> ChaptersSheet(app) { sheet = null }
        "speed" -> SpeedSheet(app) { sheet = null }
        "sleep" -> SleepSheet(app) { sheet = null }
        "marked" -> MarkedSheet(app, marked) { sheet = null }
    }
}

/**
 * The chapter's bar, with where in it and how much of it is left, and
 * beneath, the whole book's: which chapter, and what's left of the book
 * at this speed.
 */
@Composable
private fun ChapterBar(app: App, book: Book, position: Long, chapterIndex: Int, accent: Color, text: Color, soft: Color) {
    val chapter = book.chapters.getOrNull(chapterIndex)
    val start = chapter?.startMs ?: 0
    val length = (chapter?.durationMs ?: book.durationMs).coerceAtLeast(1)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: ((position - start).toFloat() / length)
    val speed = app.playback.state.collectAsState().value.speed
    ThinSlider(
        value = fraction,
        fill = accent,
        track = text.copy(alpha = 0.16f),
        thumb = text,
        onChange = { dragging = it },
        onDone = {
            dragging?.let { app.playback.seekTo(start + (it * length).toLong()) }
            dragging = null
        },
    )
    // One bar, the chapter's: precise to aim in. The whole book is told in words beneath it.
    // A book of one chapter: the bar is the book's, and the line below says it all.
    if (book.chapters.size > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(clock((fraction * length).toLong()), style = MaterialTheme.typography.labelMedium, color = soft)
        Text(
            "Chapter ${chapterIndex + 1} of ${book.chapters.size}",
            style = MaterialTheme.typography.labelMedium, color = soft, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Text("-" + clock(((1 - fraction) * length / speed).toLong()), style = MaterialTheme.typography.labelMedium, color = soft)
    }
    Spacer(Modifier.height(if (book.chapters.size > 1) 12.dp else 4.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${clock(position)} of ${clock(book.durationMs)}", style = MaterialTheme.typography.labelMedium, color = soft)
        Text(
            "${(position * 100 / book.durationMs.coerceAtLeast(1))}%",
            style = MaterialTheme.typography.labelLarge, color = text, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Text("${clock(((book.durationMs - position) / speed).toLong())} left", style = MaterialTheme.typography.labelMedium, color = soft)
    }
}

@Composable
private fun Transport(app: App, playing: Boolean, back: Int, ahead: Int, outer: Int, accent: Color, text: Color, ground: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        // The outer pair: chapters, or a longer jump when set so.
        if (outer > 0) {
            SkipButton(outer, forward = false, tint = text.copy(alpha = 0.8f), small = true, onClick = app.playback::outerBack)
        } else {
            IconButton(onClick = app.playback::previousChapter, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.SkipPrevious, "Previous chapter", Modifier.size(30.dp), tint = text.copy(alpha = 0.8f))
            }
        }
        SkipButton(back, forward = false, tint = text, onClick = app.playback::rewind)
        Box(
            Modifier
                .size(80.dp)
                .shadow(12.dp, CircleShape)
                .clip(CircleShape)
                .background(text)
                .clickable(onClick = app.playback::toggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(40.dp), tint = ground)
        }
        SkipButton(ahead, forward = true, tint = text, onClick = app.playback::forward)
        if (outer > 0) {
            SkipButton(outer, forward = true, tint = text.copy(alpha = 0.8f), small = true, onClick = app.playback::outerForward)
        } else {
            IconButton(onClick = app.playback::nextChapter, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.SkipNext, "Next chapter", Modifier.size(30.dp), tint = text.copy(alpha = 0.8f))
            }
        }
    }
}

/** A circling arrow with its seconds inside: drawn, so any number fits. */
@Composable
private fun SkipButton(seconds: Int, forward: Boolean, tint: Color, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(if (small) 52.dp else 60.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(if (small) 34.dp else 40.dp)) {
            val stroke = 2.4.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            // Open towards the arrow's side; the arrow at the top points the way it turns.
            drawArc(
                tint, if (forward) -30f else -90f, 300f, false, topLeft, arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            // The arrowhead at the arc's open end, at the top.
            val cx = size.width / 2
            val tipY = inset
            val head = 5.dp.toPx()
            val dir = if (forward) 1f else -1f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx + dir * head * 0.2f, tipY - head)
                lineTo(cx + dir * head * 1.3f, tipY)
                lineTo(cx + dir * head * 0.2f, tipY + head)
                close()
            }
            drawPath(path, tint)
        }
        Text(shortSpan(seconds), fontSize = if (small) 10.sp else 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Manrope, color = tint)
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, active: Boolean, accent: Color, soft: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .widthIn(min = 92.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) accent.copy(alpha = 0.2f) else soft.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (active) accent else soft)
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) accent else soft, maxLines = 1)
    }
}

/** Light status and navigation icons while the player's dark ground is behind them. */
@Composable
private fun LightBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    val lightTheme = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    androidx.compose.runtime.DisposableEffect(lightTheme) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            bars?.isAppearanceLightStatusBars = lightTheme
            bars?.isAppearanceLightNavigationBars = lightTheme
        }
    }
}
