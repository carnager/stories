package io.github.carnager.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import io.github.carnager.stories.App
import io.github.carnager.stories.data.Book
import io.github.carnager.stories.data.NaturalOrder
import io.github.carnager.stories.data.Status
import io.github.carnager.stories.data.status
import java.text.Normalizer

private enum class Order(val label: String) { Title("Title"), Author("Author"), Recent("Last heard"), Length("Length") }

/** Books under headings: who wrote them, which series, who reads them. */
private enum class Group(val label: String, val of: (Book) -> String) {
    None("Nothing", { "" }),
    Author("Author", { it.author }),
    Series("Series", { it.series }),
    Narrator("Reader", { it.narrator }),
}

/** Every book, wherever it lives: to find one by name, or by what's left to hear. */
@Composable
fun BooksScreen(app: App, nav: Nav) {
    val books by app.library.books.collectAsState()
    val progress by app.library.progress.collectAsState()
    val settings by app.settings.state.collectAsState()
    val state by app.playback.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<Status?>(null) }
    var order by rememberSaveable { mutableStateOf(Order.Title) }
    var group by rememberSaveable { mutableStateOf(Group.None) }
    var sorting by remember { mutableStateOf(false) }

    val shown = remember(books, progress, query, filter, order) {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        books.asSequence()
            .filter { filter == null || progress[it.id].status() == filter }
            .filter { book ->
                if (words.isEmpty()) true
                else {
                    val text = fold("${book.title} ${book.author} ${book.narrator} ${book.series} ${book.folder.substringAfterLast('/')}")
                    words.all { it in text }
                }
            }
            .toList()
            .let { list ->
                when (order) {
                    Order.Title -> list.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
                    Order.Author -> list.sortedWith(compareBy<Book> { it.author.ifEmpty { "￿" }.lowercase() }
                        .thenComparator { a, b -> NaturalOrder.compare(a.series + a.folder, b.series + b.folder) })
                    Order.Recent -> list.sortedByDescending { progress[it.id]?.lastPlayed ?: 0 }
                    Order.Length -> list.sortedBy { it.durationMs }
                }
            }
    }

    // Named groups by name, with the books that have none at the end.
    val sections = remember(shown, group) {
        if (group == Group.None) listOf("" to shown)
        else shown.groupBy { group.of(it).trim() }.toList()
            .sortedWith { a, b -> if (a.first.isEmpty() != b.first.isEmpty()) (if (a.first.isEmpty()) 1 else -1) else NaturalOrder.compare(a.first, b.first) }
            .map { (name, books) -> name.ifEmpty { "No ${group.label.lowercase()}" } to books }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Books", "${shown.size} of ${books.size}") {
            Box {
                IconButton(onClick = { sorting = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Order", tint = LocalTones.current.secondary) }
                DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                    Text("Order", style = MaterialTheme.typography.labelMedium, color = LocalTones.current.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Order.entries.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(o.label) },
                            trailingIcon = { if (o == order) Icon(Icons.Default.Check, null) },
                            onClick = { order = o; sorting = false },
                        )
                    }
                    Text("Under headings by", style = MaterialTheme.typography.labelMedium, color = LocalTones.current.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Group.entries.forEach { g ->
                        DropdownMenuItem(
                            text = { Text(g.label) },
                            trailingIcon = { if (g == group) Icon(Icons.Default.Check, null) },
                            onClick = { group = g; sorting = false },
                        )
                    }
                }
            }
            IconButton(onClick = { app.settings.update { it.copy(grid = !it.grid) } }) {
                Icon(if (settings.grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, "Change view", tint = LocalTones.current.secondary)
            }
        }
        SearchField(query) { query = it }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Choice("All", filter == null) { filter = null }
            Choice("New", filter == Status.New) { filter = Status.New }
            Choice("Listening", filter == Status.Listening) { filter = Status.Listening }
            Choice("Finished", filter == Status.Finished) { filter = Status.Finished }
        }
        if (settings.grid) {
            LazyVerticalGrid(
                GridCells.Adaptive(132.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sections.forEach { (heading, books) ->
                    if (heading.isNotEmpty()) item(key = "h:$heading", span = { GridItemSpan(maxLineSpan) }) { Heading(heading, books.size) }
                    items(books, key = { it.id }) { book ->
                        BookTile(book, book.title, progress[book.id], book.id == state.bookId, { nav.book = book.id }, { nav.holding = Hold.OnBook(book) })
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                sections.forEach { (heading, books) ->
                    if (heading.isNotEmpty()) item(key = "h:$heading") { Heading(heading, books.size, inset = 20) }
                    items(books, key = { it.id }) { book ->
                        BookRow(book, book.title, progress[book.id], book.id == state.bookId, { nav.book = book.id }, { nav.holding = Hold.OnBook(book) })
                    }
                }
            }
        }
    }
}

/** Lower case, accents off: "Hörbuch" is found by "horbuch". */
private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LocalTones.current.raised)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = LocalTones.current.muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Title, author, narrator, series", style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.muted)
            BasicTextField(
                query, onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Default.Close, "Clear", Modifier.size(20.dp).clickable { onChange("") }, tint = LocalTones.current.muted)
        }
    }
}

@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else LocalTones.current.secondary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else LocalTones.current.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Heading(name: String, count: Int, inset: Int = 8) {
    Row(Modifier.fillMaxWidth().padding(start = inset.dp, end = inset.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text("$count", style = MaterialTheme.typography.labelMedium, color = LocalTones.current.muted)
    }
}
