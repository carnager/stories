package io.github.carnager.stories.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.carnager.stories.App
import io.github.carnager.stories.data.Book
import io.github.carnager.stories.data.Edit

/** Starts picking books to join, with [book] picked, in the folder it's listed in. */
fun startJoining(app: App, nav: Nav, book: Book) {
    nav.joining.clear()
    nav.joining.add(book.id)
    nav.folders.clear()
    nav.folders.addAll(app.library.pathTo(book))
    nav.book = null
    nav.tab = 1
}

/**
 * The ways a book's shape and details can be put right by hand. None
 * touches the files; each can be undone here.
 */
@Composable
fun BookMenu(app: App, nav: Nav, book: Book) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Edit details") }, onClick = { open = false; editing = true })
            DropdownMenuItem(text = { Text("Join with other books…") }, onClick = { open = false; startJoining(app, nav, book) })
            if (book.joined || book.fromDiscs) {
                DropdownMenuItem(
                    text = { Text(if (book.joined) "Undo join" else "Keep its folders apart") },
                    onClick = { open = false; nav.book = null; app.library.unmerge(book) },
                )
            }
            if (book.tracks.size > 1 && !book.joined && !book.fromDiscs) {
                DropdownMenuItem(text = { Text("Make each file a book") }, onClick = { open = false; nav.book = null; app.library.cutPerFile(book) })
            }
            if (app.settings.value.allowDelete) {
                DropdownMenuItem(
                    text = { Text("Delete from phone…", color = MaterialTheme.colorScheme.error) },
                    onClick = { open = false; nav.deleting = book.title to listOf(book) },
                )
            }
            if (book.single && app.library.isCut(book.folder)) {
                DropdownMenuItem(
                    text = { Text("Join the folder's files back into one book") },
                    onClick = { open = false; nav.book = null; app.library.uncut(book.folder) },
                )
            }
        }
    }
    if (editing) EditDialog(app, book) { editing = false }
}

@Composable
private fun EditDialog(app: App, book: Book, onDone: () -> Unit) {
    var title by remember { mutableStateOf(book.title) }
    var author by remember { mutableStateOf(book.author) }
    var narrator by remember { mutableStateOf(book.narrator) }
    var series by remember { mutableStateOf(book.series) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Title", title) { title = it }
                Field("Author", author) { author = it }
                Field("Read by", narrator) { narrator = it }
                Field("Series", series) { series = it }
                Text(
                    "Kept by Stories; the files' tags stay as they are.",
                    style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.library.edit(book, Edit(title.trim().ifEmpty { book.title }, author.trim(), narrator.trim(), series.trim()))
                onDone()
            }) { Text("Save") }
        },
        dismissButton = {
            if (book.edited) {
                TextButton(onClick = { app.library.edit(book, null); onDone() }) { Text("Use the tags") }
            } else {
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

/**
 * The question before deleting: what goes, how much, and that it can't be
 * undone. Only a yes deletes; the files go, then what Stories kept of them.
 */
@Composable
fun DeleteDialog(app: App, name: String, books: List<Book>, onDeleted: () -> Unit, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val size by androidx.compose.runtime.produceState<Pair<Int, Long>?>(null, books) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.carnager.stories.data.Deleter.measure(books) }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (books.size == 1) "Delete \u201C$name\u201D?" else "Delete ${books.size} books in \u201C$name\u201D?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    size?.let { (files, bytes) ->
                        val amount = android.text.format.Formatter.formatShortFileSize(context, bytes)
                        if (files == 1) "1 file, $amount, is deleted from the phone." else "$files files, $amount, are deleted from the phone."
                    }
                        ?: "Counting the files…",
                )
                Text(
                    "With them go covers, CUE sheets and position files beside them, empty folders, and the places and bookmarks Stories kept. This can't be undone.",
                    style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDone()
                onDeleted()
                app.deleteBooks(books) { result ->
                    val freed = android.text.format.Formatter.formatShortFileSize(context, result.bytes)
                    val files = if (result.deleted == 1) "1 file" else "${result.deleted} files"
                    val text = if (result.failed.isEmpty()) "Deleted $files, $freed"
                    else "Deleted $files; ${result.failed.size} could not be deleted"
                    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
