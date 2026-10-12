package io.github.carnager.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
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
import androidx.compose.ui.unit.dp
import io.github.carnager.stories.App
import io.github.carnager.stories.data.Entry

/**
 * The folders as they are on the phone. A folder that is one book shows as
 * that book, its cover marked with how far it's heard; a folder of books --
 * a series -- shows their covers stacked and how many are done.
 */
@Composable
fun FoldersScreen(app: App, nav: Nav) = androidx.compose.foundation.layout.Box {
    FolderList(app, nav)
    if (nav.joining.isNotEmpty()) {
        androidx.activity.compose.BackHandler { nav.joining.clear() }
        JoinBar(app, nav)
    }
}

@Composable
private fun FolderList(app: App, nav: Nav) {
    val books by app.library.books.collectAsState()
    val progress by app.library.progress.collectAsState()
    val settings by app.settings.state.collectAsState()
    val state by app.playback.state.collectAsState()
    // The top is the one chosen folder itself, or the list of them when there are several.
    val folder = nav.folders.lastOrNull() ?: app.library.rootFolder()
    val entries = remember(books, folder) { app.library.entries(folder) }
    val here = remember(books, folder) { folder?.let { app.library.folderBooks(it) } ?: books }
    val t = tally(here, progress)
    val title = if (nav.folders.isEmpty()) "Folders" else folder!!.substringAfterLast('/')

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (nav.folders.isNotEmpty()) {
                IconButton(onClick = { nav.folders.removeAt(nav.folders.lastIndex) }, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                }
            }
            Column(Modifier.weight(1f).then(if (nav.folders.isNotEmpty()) Modifier.offset(x = (-14).dp) else Modifier)) {
                ScreenHeader(title, if (here.isEmpty()) null else t.line) {
                    IconButton(onClick = { app.settings.update { it.copy(grid = !it.grid) } }) {
                        Icon(
                            if (settings.grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            if (settings.grid) "Show as list" else "Show as covers",
                            tint = LocalTones.current.secondary,
                        )
                    }
                }
            }
        }
        if (nav.folders.size > 1) Breadcrumbs(nav)

        val picking = nav.joining.isNotEmpty()
        val open: (Entry) -> Unit = { entry ->
            when (entry) {
                is Entry.Folder -> nav.folders.add(entry.path)
                is Entry.Item -> if (picking) pick(nav, entry.book.id) else nav.book = entry.book.id
            }
        }
        val hold: (Entry) -> Unit = { entry ->
            when {
                picking && entry is Entry.Item -> pick(nav, entry.book.id)
                entry is Entry.Folder -> nav.holding = Hold.OnFolder(entry)
                entry is Entry.Item -> nav.holding = Hold.OnBook(entry.book)
            }
        }
        val picked: (Entry.Item) -> Boolean? = { if (picking) it.book.id in nav.joining else null }
        if (entries.isEmpty()) {
            Text(
                "Nothing here yet.",
                style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.secondary,
                modifier = Modifier.padding(20.dp),
            )
        } else if (settings.grid) {
            LazyVerticalGrid(
                GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries, key = { it.key() }) { entry ->
                    when (entry) {
                        is Entry.Folder -> FolderTile(entry.name, entry.books, progress, { open(entry) }, { hold(entry) })
                        is Entry.Item -> BookTile(entry.book, entry.name, progress[entry.book.id], entry.book.id == state.bookId, { open(entry) }, { hold(entry) }, picked(entry))
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries, key = { it.key() }) { entry ->
                    when (entry) {
                        is Entry.Folder -> FolderRow(entry.name, entry.books, progress, { open(entry) }, { hold(entry) })
                        is Entry.Item -> BookRow(entry.book, entry.name, progress[entry.book.id], entry.book.id == state.bookId, { open(entry) }, { hold(entry) }, picked(entry))
                    }
                }
            }
        }
    }
}

private fun pick(nav: Nav, id: String) {
    if (id in nav.joining) nav.joining.remove(id) else nav.joining.add(id)
}

/**
 * While picking books to join: how many, and the button that joins them.
 * Books from different folders may be joined too; the book then lives in
 * the folder they share.
 */
@Composable
private fun JoinBar(app: App, nav: Nav) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (nav.joining.size < 2) "Pick the books to join" else "${nav.joining.size} books picked",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = { nav.joining.clear() }) {
                Text("Cancel", color = MaterialTheme.colorScheme.inverseOnSurface)
            }
            androidx.compose.material3.TextButton(
                enabled = nav.joining.size >= 2,
                onClick = {
                    app.library.merge(nav.joining.mapNotNull { app.library.book(it) })
                    nav.joining.clear()
                },
            ) {
                Text("Join", color = MaterialTheme.colorScheme.inversePrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun Entry.key() = when (this) {
    is Entry.Folder -> "f:$path"
    is Entry.Item -> "b:${book.id}"
}

/** The way down from the top, each step a tap back up to it. */
@Composable
private fun Breadcrumbs(nav: Nav) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        nav.folders.dropLast(1).forEachIndexed { i, path ->
            Text(
                path.substringAfterLast('/'),
                style = MaterialTheme.typography.labelMedium,
                color = LocalTones.current.secondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { while (nav.folders.size > i + 1) nav.folders.removeAt(nav.folders.lastIndex) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = LocalTones.current.faint)
        }
    }
}
