package io.github.carnager.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.carnager.stories.App
import io.github.carnager.stories.data.Status
import io.github.carnager.stories.data.status

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, full: Boolean = false, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !full),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(bottom = 16.dp)) { content() }
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted)
    }
}

@Composable
fun ChaptersSheet(app: App, onDismiss: () -> Unit) {
    val book = app.playback.book ?: return onDismiss()
    val state by app.playback.state.collectAsState()
    val position = livePosition(app.playback, state.playing)
    val current = book.chapterAt(position)
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem((current - 2).coerceAtLeast(0)) }
    Sheet(onDismiss, full = true) {
        SheetTitle("Chapters", "${book.chapters.size} · ${span(book.durationMs)}")
        LazyColumn(state = list) {
            itemsIndexed(book.chapters) { i, chapter ->
                ChapterRow(i, chapter.title, chapter.durationMs, i == current, i < current) {
                    app.playback.seekTo(chapter.startMs)
                    onDismiss()
                }
            }
        }
    }
}

/** Speed: a step at a time, or one of the usual ones. Kept per book. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(app: App, onDismiss: () -> Unit) {
    val state by app.playback.state.collectAsState()
    Sheet(onDismiss) {
        SheetTitle("Speed", "For this book")
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Default.Remove, "Slower") { app.playback.setSpeed(state.speed - 0.05f) }
            Text(speedLabel(state.speed), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            RoundButton(Icons.Default.Add, "Faster") { app.playback.setSpeed(state.speed + 0.05f) }
        }
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.8f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2f, 2.5f).forEach { s ->
                Choice(speedLabel(s), kotlin.math.abs(state.speed - s) < 0.01f) { app.playback.setSpeed(s) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepSheet(app: App, onDismiss: () -> Unit) {
    val sleep by app.playback.sleep.state.collectAsState()
    val settings by app.settings.state.collectAsState()
    Sheet(onDismiss) {
        SheetTitle(
            "Sleep timer",
            sleep?.let { if (it.endOfChapter) "Stops at the chapter's end" else "Stops in ${clock(it.remainingMs)}" }
                ?: if (settings.shakeToExtend) "Shake the phone to start it over" else "Counts while playing",
        )
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 20, 30, 45, 60, 90).forEach { m ->
                Choice("$m min", sleep?.let { !it.endOfChapter && it.totalMs == m * 60_000L } == true) {
                    app.playback.sleep.start(m)
                    app.settings.update { it.copy(sleepMinutes = m) }
                    onDismiss()
                }
            }
            Choice("End of chapter", sleep?.endOfChapter == true) {
                app.playback.sleep.endOfChapter()
                onDismiss()
            }
        }
        if (sleep != null) {
            Spacer(Modifier.height(8.dp))
            ActionRow(Icons.Default.Remove, "Turn off") { app.playback.sleep.cancel(); onDismiss() }
        }
    }
}

/** After a bookmark is set: where it is, and a line to say why, if wanted. */
@Composable
fun MarkedSheet(app: App, id: Long, onDismiss: () -> Unit) {
    val book = app.playback.book ?: return onDismiss()
    val marks by app.library.bookmarks.collectAsState()
    val mark = marks.firstOrNull { it.id == id } ?: return onDismiss()
    var note by remember { mutableStateOf(mark.note) }
    val save = { app.library.noteBookmark(id, note); onDismiss() }
    Sheet(save) {
        SheetTitle(
            "Bookmarked at ${clock(mark.positionMs)}",
            book.chapters.getOrNull(book.chapterAt(mark.positionMs))?.title?.takeIf { it.isNotBlank() } ?: book.title,
        )
        androidx.compose.material3.OutlinedTextField(
            note, { note = it },
            placeholder = { Text("A note, if you like") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            androidx.compose.material3.TextButton(onClick = { app.library.removeBookmark(id); onDismiss() }) { Text("Remove") }
            androidx.compose.material3.TextButton(onClick = save) { Text("Done") }
        }
    }
}

/** What a long press offers: for a book, playing and marking it; for a folder, marking all of it. */
@Composable
fun HoldSheet(app: App, nav: Nav, hold: Hold, onDismiss: () -> Unit) {
    val progress by app.library.progress.collectAsState()
    Sheet(onDismiss) {
        when (hold) {
            is Hold.OnBook -> {
                val book = hold.book
                Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusCover(book, progress[book.id], Modifier.size(56.dp), corner = 8.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(statusLine(book, progress[book.id]), style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted)
                    }
                }
                val status = progress[book.id].status()
                ActionRow(Icons.Default.PlayArrow, if (status == Status.Listening) "Continue" else "Play") {
                    app.playback.play(book); onDismiss(); nav.player = true
                }
                if (status != Status.Finished) ActionRow(Icons.Default.Check, "Mark as finished") { app.library.markFinished(listOf(book), true); onDismiss() }
                if (status != Status.New) ActionRow(Icons.Default.RestartAlt, "Mark as new") { app.library.markFinished(listOf(book), false); onDismiss() }
                ActionRow(Icons.Default.Info, "Details and chapters") { nav.book = book.id; onDismiss() }
                ActionRow(Icons.Default.Add, "Join with other books…") { onDismiss(); startJoining(app, nav, book) }
            }
            is Hold.OnFolder -> {
                val books = hold.entry.books
                SheetTitle(hold.entry.name, tally(books, progress).line)
                val first = books.firstOrNull { progress[it.id].status() != Status.Finished }
                if (first != null) {
                    ActionRow(Icons.Default.PlayArrow, "Play the first unfinished") { app.playback.play(first); onDismiss(); nav.player = true }
                }
                ActionRow(Icons.Default.Check, "Mark all as finished") { app.library.markFinished(books, true); onDismiss() }
                ActionRow(Icons.Default.Replay, "Mark all as new") { app.library.markFinished(books, false); onDismiss() }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = LocalTones.current.secondary)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(LocalTones.current.raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label)
    }
}
