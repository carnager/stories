package io.github.carnager.stories.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.carnager.stories.App
import io.github.carnager.stories.data.Book
import io.github.carnager.stories.data.Entry

/** Where the screens are: which tab, which folder, which book or overlay is open. */
@Stable
class Nav {
    var tab by mutableIntStateOf(0)
    /** The folders opened, outermost first; empty is the top. */
    val folders = mutableStateListOf<String>()
    var book by mutableStateOf<String?>(null)
    var player by mutableStateOf(false)
    var settings by mutableStateOf(false)
    var holding by mutableStateOf<Hold?>(null)

    /** Books asked to be deleted, with what to call them, awaiting the yes. */
    var deleting by mutableStateOf<Pair<String, List<Book>>?>(null)

    /** Books picked to be joined into one; empty when not picking. */
    val joining = mutableStateListOf<String>()
}

/** What a long press was on, for its sheet of actions. */
sealed interface Hold {
    data class OnBook(val book: Book) : Hold
    data class OnFolder(val entry: Entry.Folder) : Hold
}

@Composable
fun MainScreen(app: App, nav: Nav) {
    val tones = LocalTones.current
    BackHandler(enabled = nav.tab == 1 && nav.folders.isNotEmpty() && nav.book == null && !nav.player && !nav.settings) {
        nav.folders.removeAt(nav.folders.lastIndex)
    }
    BackHandler(enabled = nav.tab != 0 && nav.folders.isEmpty() && nav.book == null && !nav.player && !nav.settings) {
        nav.tab = 0
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    MiniPlayer(app) { nav.player = true }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = tones.muted,
                            unselectedTextColor = tones.muted,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        )
                        listOf(
                            Triple("Listening", Icons.Default.Headphones, 0),
                            Triple("Folders", Icons.Default.Folder, 1),
                            Triple("Books", Icons.AutoMirrored.Filled.LibraryBooks, 2),
                        ).forEach { (label, icon, index) ->
                            NavigationBarItem(
                                selected = nav.tab == index,
                                onClick = {
                                    // A second tap on Folders goes back to the top.
                                    if (index == 1 && nav.tab == 1) nav.folders.clear()
                                    nav.tab = index
                                },
                                icon = { Icon(icon, null) },
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                colors = colors,
                            )
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                Crossfade(nav.tab, label = "tab") { tab ->
                    when (tab) {
                        0 -> HomeScreen(app, nav)
                        1 -> FoldersScreen(app, nav)
                        else -> BooksScreen(app, nav)
                    }
                }
            }
        }

        AnimatedVisibility(nav.book != null, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
            // Kept while it slides away, so it doesn't go blank on the way out.
            val shown = remember { mutableStateOf<String?>(null) }
            nav.book?.let { shown.value = it }
            bookFor(app, shown.value)?.let { BookScreen(app, nav, it) }
        }
        AnimatedVisibility(nav.player, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            PlayerScreen(app) { nav.player = false }
        }
        AnimatedVisibility(nav.settings, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            SettingsScreen(app) { nav.settings = false }
        }
        nav.holding?.let { HoldSheet(app, nav, it) { nav.holding = null } }
        nav.deleting?.let { (name, books) ->
            DeleteDialog(app, name, books, onDeleted = { if (nav.book in books.map { it.id }) nav.book = null }) { nav.deleting = null }
        }
    }
}

/** The book playing, small, over the tabs: tap for the player. */
@Composable
fun MiniPlayer(app: App, onOpen: () -> Unit) {
    val state by app.playback.state.collectAsState()
    val book = bookFor(app, state.bookId) ?: return
    val position = livePosition(app.playback, state.playing)
    val chapter = book.chapters.getOrNull(book.chapterAt(position))
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen),
        color = LocalTones.current.raised,
    ) {
        Column {
            Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(book, Modifier.size(46.dp), corner = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.error ?: chapter?.title?.takeIf { it.isNotBlank() && it != book.title } ?: book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else LocalTones.current.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = app.playback::rewind) {
                    Icon(Icons.Default.Replay, "Back ${app.settings.value.rewindSeconds} seconds", tint = LocalTones.current.secondary)
                }
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = app.playback::toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (state.playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            ProgressLine(if (book.durationMs > 0) position.toFloat() / book.durationMs else 0f, height = 2.dp, track = LocalTones.current.hairline)
        }
    }
}
