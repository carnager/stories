package io.github.carnager.stories.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.carnager.stories.App
import io.github.carnager.stories.data.Book
import io.github.carnager.stories.data.Status
import io.github.carnager.stories.data.status

/** One book: its cover and facts, the button to play it, and its chapters to start from. */
@Composable
fun BookScreen(app: App, nav: Nav, book: Book) {
    BackHandler { nav.book = null }
    val progress by app.library.progress.collectAsState()
    val bookmarks by app.library.bookmarks.collectAsState()
    val state by app.playback.state.collectAsState()
    val p = progress[book.id]
    val isCurrent = state.bookId == book.id
    val position = if (isCurrent) livePosition(app.playback, state.playing) else p?.positionMs ?: 0
    val currentChapter = if (p.status() == Status.New && !isCurrent) -1 else book.chapterAt(position)
    val marks = remember(bookmarks, book.id) { bookmarks.filter { it.bookId == book.id }.sortedBy { it.positionMs } }
    val tones = LocalTones.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp)) {
                    IconButton(onClick = { nav.book = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    BookMenu(app, nav, book)
                }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Box(Modifier.width(150.dp).shadow(14.dp, RoundedCornerShape(12.dp))) {
                        StatusCover(book, p, Modifier.fillMaxWidth(), corner = 12.dp)
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        if (book.series.isNotEmpty()) {
                            Text(book.series.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(book.title, style = MaterialTheme.typography.headlineSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        if (book.author.isNotEmpty()) Text(book.author, style = MaterialTheme.typography.bodyMedium, color = tones.secondary)
                        if (book.narrator.isNotEmpty() && book.narrator != book.author) {
                            Text("Read by ${book.narrator}", style = MaterialTheme.typography.bodySmall, color = tones.muted)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOfNotNull(book.year.ifEmpty { null }, span(book.durationMs),
                                if (book.chapters.size > 1) "${book.chapters.size} chapters" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = tones.muted,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                if (p.status() == Status.Listening || isCurrent) {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        ProgressLine(position.toFloat() / book.durationMs.coerceAtLeast(1), height = 4.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(position * 100 / book.durationMs.coerceAtLeast(1))}% heard · ${span(book.durationMs - position)} left",
                            style = MaterialTheme.typography.bodySmall, color = tones.secondary,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    val playingThis = isCurrent && state.playing
                    Pill(
                        when {
                            playingThis -> "Pause"
                            p.status() == Status.Listening -> "Continue"
                            p.status() == Status.Finished -> "Listen again"
                            else -> "Play"
                        },
                        if (playingThis) Icons.Default.Pause else if (p.status() == Status.Finished) Icons.Default.Replay else Icons.Default.PlayArrow,
                        onClick = {
                            if (playingThis) app.playback.pause()
                            else {
                                app.playback.play(book)
                                nav.player = true
                            }
                        },
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    if (p.status() == Status.Finished) {
                        Pill("Mark as new", Icons.Default.Close, onClick = { app.library.markFinished(listOf(book), false) })
                    } else {
                        Pill("Mark finished", Icons.Default.Check, onClick = { app.library.markFinished(listOf(book), true) })
                    }
                }
            }
            if (marks.isNotEmpty()) {
                item { SectionLabel("Bookmarks") }
                items(marks, key = { "m${it.id}" }) { mark ->
                    var noting by remember { mutableStateOf(false) }
                    if (noting) NoteDialog(mark.note, { app.library.noteBookmark(mark.id, it) }) { noting = false }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { app.playback.play(book, mark.positionMs); nav.player = true }
                            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Bookmark, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mark.note.ifBlank { book.chapters.getOrNull(book.chapterAt(mark.positionMs))?.title.orEmpty() },
                                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(clock(mark.positionMs), style = MaterialTheme.typography.bodySmall, color = tones.muted)
                        }
                        IconButton(onClick = { noting = true }) {
                            Icon(Icons.Default.Edit, "Note", Modifier.size(18.dp), tint = tones.muted)
                        }
                        IconButton(onClick = { app.library.removeBookmark(mark.id) }) {
                            Icon(Icons.Default.Close, "Remove bookmark", Modifier.size(18.dp), tint = tones.muted)
                        }
                    }
                }
            }
            item { SectionLabel(if (book.chapters.size > 1) "Chapters" else "File") }
            itemsIndexed(book.chapters, key = { i, _ -> "c$i" }) { i, chapter ->
                ChapterRow(i, chapter.title, chapter.durationMs, i == currentChapter, i < currentChapter || p.status() == Status.Finished) {
                    app.playback.play(book, chapter.startMs)
                    nav.player = true
                }
            }
            item {
                Text(
                    book.folder,
                    style = MaterialTheme.typography.bodySmall, color = tones.faint,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                )
            }
        }
    }
}

/** A chapter: its number, name and length; the one at hand marked, those behind quieter. */
@Composable
fun ChapterRow(index: Int, title: String, durationMs: Long, current: Boolean, heard: Boolean, onClick: () -> Unit) {
    val tones = LocalTones.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (current) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) else Modifier)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(34.dp)) {
            if (current) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).align(Alignment.CenterStart))
            } else {
                Text("${index + 1}", style = MaterialTheme.typography.bodySmall, color = tones.muted)
            }
        }
        Text(
            title.ifBlank { "Chapter ${index + 1}" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (current) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = when {
                current -> MaterialTheme.colorScheme.primary
                heard -> tones.muted
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(clock(durationMs), style = MaterialTheme.typography.bodySmall, color = tones.muted)
    }
}

@Composable
private fun NoteDialog(note: String, onSave: (String) -> Unit, onDone: () -> Unit) {
    var text by remember { mutableStateOf(note) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Note") },
        text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(text); onDone() }) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
