package io.github.carnager.stories.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.carnager.stories.data.Book
import io.github.carnager.stories.data.Progress
import io.github.carnager.stories.data.Status
import io.github.carnager.stories.data.status

/** How far a book is, in words: for under its cover. */
fun statusLine(book: Book, progress: Progress?): String = when (progress.status()) {
    Status.New -> "New · ${span(book.durationMs)}"
    Status.Finished -> "Finished"
    Status.Listening -> "${(progress!!.fraction * 100).toInt()}% · ${span(book.durationMs - progress.positionMs)} left"
}

@Composable
private fun statusColor(progress: Progress?): Color = when (progress.status()) {
    Status.New -> MaterialTheme.colorScheme.primary
    Status.Finished -> LocalTones.current.done
    Status.Listening -> LocalTones.current.secondary
}

/** A book in a grid: its cover, which is how it's known, and its name and state beneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookTile(book: Book, name: String, progress: Progress?, playing: Boolean, onOpen: () -> Unit, onHold: () -> Unit, picked: Boolean? = null) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onHold)
            .padding(6.dp),
    ) {
        Box {
            StatusCover(book, progress, Modifier.fillMaxWidth(), playing = playing)
            if (picked != null) PickMark(picked, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            name, style = bookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (progress.status() == Status.Finished) LocalTones.current.secondary else MaterialTheme.colorScheme.onSurface,
        )
        Text(statusLine(book, progress), style = MaterialTheme.typography.bodySmall, color = statusColor(progress), maxLines = 1)
    }
}

/** A book as a row, for the list view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookRow(book: Book, name: String, progress: Progress?, playing: Boolean, onOpen: () -> Unit, onHold: () -> Unit, picked: Boolean? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onHold)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            StatusCover(book, progress, Modifier.size(60.dp), corner = 8.dp, playing = playing)
            if (picked != null) PickMark(picked, Modifier.align(Alignment.TopStart).padding(3.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name, style = bookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (progress.status() == Status.Finished) LocalTones.current.secondary else MaterialTheme.colorScheme.onSurface,
            )
            val by = book.author.takeIf { it.isNotEmpty() && it != name }
            if (by != null) Text(by, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(statusLine(book, progress), style = MaterialTheme.typography.bodySmall, color = statusColor(progress), maxLines = 1)
        }
    }
}

data class Tally(val total: Int, val finished: Int, val listening: Int) {
    val line: String
        get() = when {
            finished == total -> "All $total heard"
            finished == 0 && listening == 0 -> "$total books · all new"
            else -> "$finished of $total heard" + if (listening > 0) " · $listening going" else ""
        }
}

fun tally(books: List<Book>, progress: Map<String, Progress>): Tally {
    var finished = 0
    var listening = 0
    books.forEach {
        when (progress[it.id].status()) {
            Status.Finished -> finished++
            Status.Listening -> listening++
            Status.New -> Unit
        }
    }
    return Tally(books.size, finished, listening)
}

/** A folder of books in a grid: their covers stacked, and how much of them is heard. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderTile(name: String, books: List<Book>, progress: Map<String, Progress>, onOpen: () -> Unit, onHold: () -> Unit) {
    val t = tally(books, progress)
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onHold)
            .padding(6.dp),
    ) {
        Box {
            FolderCover(books, Modifier.fillMaxWidth())
            CountTab("${books.size}", Modifier.align(Alignment.TopStart).padding(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        ProgressLine((t.finished + 0.5f * t.listening) / t.total.coerceAtLeast(1), color = LocalTones.current.done)
        Spacer(Modifier.height(3.dp))
        Text(t.line, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderRow(name: String, books: List<Book>, progress: Map<String, Progress>, onOpen: () -> Unit, onHold: () -> Unit) {
    val t = tally(books, progress)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onHold)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderCover(books, Modifier.size(60.dp), corner = 7.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            ProgressLine((t.finished + 0.5f * t.listening) / t.total.coerceAtLeast(1), color = LocalTones.current.done)
            Spacer(Modifier.height(4.dp))
            Text(t.line, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LocalTones.current.faint)
    }
}

/** Picked or not, while picking books to join: a filled tick or an empty ring on the cover. */
@Composable
fun PickMark(picked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(24.dp)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(if (picked) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f))
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (picked) Icon(Icons.Default.Check, "Picked", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}
