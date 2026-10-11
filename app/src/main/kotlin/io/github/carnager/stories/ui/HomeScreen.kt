package io.github.carnager.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.carnager.stories.App
import io.github.carnager.stories.data.Book
import io.github.carnager.stories.data.Status
import io.github.carnager.stories.data.status

/**
 * The first screen: the book being heard, large, one tap from going on;
 * what comes next in its series; and the other books left half-way.
 */
@Composable
fun HomeScreen(app: App, nav: Nav) {
    val books by app.library.books.collectAsState()
    val progress by app.library.progress.collectAsState()
    val state by app.playback.state.collectAsState()
    val scanning by app.library.scanning.collectAsState()
    val listening = remember(books, progress) {
        books.filter { progress[it.id].status() == Status.Listening }.sortedByDescending { progress[it.id]?.lastPlayed ?: 0 }
    }
    // The book in the player, or else the one heard last: either way, one tap goes on with it.
    val current = bookFor(app, state.bookId) ?: listening.firstOrNull()
    val going = remember(listening, current) { listening.filter { it.id != current?.id } }
    val finished = remember(books, progress) {
        books.filter { progress[it.id].status() == Status.Finished }.sortedByDescending { progress[it.id]?.lastPlayed ?: 0 }.take(12)
    }
    // Only a series has a next: loose books side by side don't follow one another.
    val next = remember(current, progress) { current?.takeIf { it.series.isNotEmpty() }?.let { app.library.nextInSeries(it) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader(
                "Stories",
                subtitle = scanning?.let { s -> if (s.total > 0) "Looking through ${s.done} of ${s.total} files…" else "Looking for books…" }
                    ?: "${books.size} books",
            ) {
                IconButton(onClick = app.library::rescan) { Icon(Icons.Default.Refresh, "Look for new books", tint = LocalTones.current.secondary) }
                IconButton(onClick = { nav.settings = true }) { Icon(Icons.Default.Tune, "Settings", tint = LocalTones.current.secondary) }
            }
        }
        if (books.isEmpty()) {
            item { Empty(scanning != null) }
            return@LazyColumn
        }
        current?.let { book ->
            item { NowCard(app, book, state.playing && state.bookId == book.id) { if (state.bookId == book.id) nav.player = true else nav.book = book.id } }
        }
        next?.let { book ->
            item {
                SectionLabel("Next in ${book.series}")
                BookRow(book, book.title, progress[book.id], false, onOpen = { nav.book = book.id }, onHold = { nav.holding = Hold.OnBook(book) })
            }
        }
        if (going.isNotEmpty()) {
            item {
                SectionLabel("Still listening")
                Shelf(going, app, nav)
            }
        }
        if (current == null && going.isEmpty()) {
            item {
                SectionLabel("Start something")
                Shelf(remember(books) { books.shuffled().take(12) }, app, nav)
            }
        }
        if (finished.isNotEmpty()) {
            item {
                SectionLabel("Finished")
                Shelf(finished, app, nav)
            }
        }
    }
}

/** A row of covers to scroll sideways. */
@Composable
private fun Shelf(books: List<Book>, app: App, nav: Nav) {
    val progress by app.library.progress.collectAsState()
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(books, key = { it.id }) { book ->
            Box(Modifier.width(132.dp)) {
                BookTile(book, book.title, progress[book.id], false, onOpen = { nav.book = book.id }, onHold = { nav.holding = Hold.OnBook(book) })
            }
        }
    }
}

/** The book being heard: its cover, where it is, and the button to go on. */
@Composable
private fun NowCard(app: App, book: Book, playing: Boolean, onOpen: () -> Unit) {
    val state by app.playback.state.collectAsState()
    val progress by app.library.progress.collectAsState()
    val loaded = state.bookId == book.id
    val live = livePosition(app.playback, playing)
    val position = if (loaded) live else progress[book.id]?.positionMs ?: 0
    val speed = if (loaded) state.speed else progress[book.id]?.speed ?: 1f
    val chapterIndex = book.chapterAt(position)
    val chapter = book.chapters.getOrNull(chapterIndex)
    val left = ((book.durationMs - position) / speed).toLong()
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(LocalTones.current.raised)
            .clickable(onClick = onOpen)
            .padding(16.dp),
    ) {
        Row {
            ProgressEdge(
                position.toFloat() / book.durationMs.coerceAtLeast(1),
                MaterialTheme.colorScheme.primary,
                Modifier.width(132.dp),
                width = 2.5.dp,
                gap = 4.dp,
                glow = false,
                underlay = false,
            ) {
                Box(Modifier.shadow(10.dp, RoundedCornerShape(10.dp))) { Cover(book, Modifier.fillMaxWidth()) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("NOW LISTENING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotEmpty()) {
                    Text(book.author, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                if (chapter != null && book.chapters.size > 1) {
                    Text(
                        "Chapter ${chapterIndex + 1} of ${book.chapters.size}",
                        style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted, maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        ProgressLine(if (book.durationMs > 0) position.toFloat() / book.durationMs else 0f, height = 4.dp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${span(left)} left", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${(position * 100 / book.durationMs.coerceAtLeast(1))}% heard" + if (speed != 1f) " · at ${speedLabel(speed)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted,
                )
            }
            Pill(
                if (playing) "Pause" else "Continue",
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                onClick = { if (loaded) app.playback.toggle() else app.playback.play(book) },
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun Empty(scanning: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (scanning) "Looking for books…" else "No books found", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            if (scanning) "The first look takes a moment; after that only new files are read."
            else "Put each book in a folder of its own inside your audiobook folder, or add another folder in settings.",
            style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.secondary,
        )
    }
}
