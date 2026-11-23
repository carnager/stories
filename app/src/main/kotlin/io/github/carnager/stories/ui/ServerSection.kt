package io.github.carnager.stories.ui

import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.carnager.stories.App
import io.github.carnager.stories.data.Book
import io.github.carnager.stories.data.ServerAddress
import kotlinx.coroutines.launch
import java.io.IOException

/** The settings' part about a server: connect, see how it goes, let go. */
@Composable
fun ServerSection(app: App) {
    val status by app.sync.status.collectAsState()
    var connecting by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    SectionLabel("Server")
    val url = status.url
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        if (url == null) {
            Text("Keep places and bookmarks in step with storiesd on your computer or home server.",
                style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted)
        } else {
            Text(url, style = MaterialTheme.typography.bodyLarge)
            val line = when {
                status.syncing -> "Syncing…"
                status.lastSync > 0 -> "Synced " + DateUtils.getRelativeTimeSpanString(status.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                else -> "Not synced yet"
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted)
        }
        status.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (url == null) {
            Pill("Connect", null, onClick = { connecting = true })
        } else {
            Pill("Sync now", null, onClick = app.sync::now)
            Pill("Disconnect", null, onClick = { leaving = true })
        }
    }
    if (url != null) {
        val cache by app.remote.cache.collectAsState()
        val settings by app.settings.state.collectAsState()
        val context = LocalContext.current
        val used = cache.values.sumOf { it.done }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text("Storage for kept books", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${Formatter.formatShortFileSize(context, used)} used. Over the limit, finished books are removed first. Books you haven't finished stay.",
                style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 5, 10, 25, 50, 100).forEach { gb ->
                    Choice(if (gb == 0) "No limit" else "$gb GB", settings.cacheLimitGb == gb) {
                        app.settings.update { it.copy(cacheLimitGb = gb) }
                    }
                }
            }
        }
    }
    if (connecting) ConnectDialog(app, "", onClose = { connecting = false })
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Disconnect from server?") },
            text = { Text("Its books leave Stories, and copies kept on this phone are removed. Nothing changes on the server, or in your own folders.") },
            confirmButton = {
                TextButton(onClick = { leaving = false; app.leaveServer() }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } },
        )
    }
}

/** While playing, a newer place came from another device: go there, or stay. */
@Composable
fun NewerPlaceDialog(app: App) {
    val place by app.sync.newer.collectAsState()
    val p = place ?: return
    val book = app.library.book(p.bookId) ?: return
    val scope = rememberCoroutineScope()
    val chapter = book.chapters.getOrNull(book.chapterAt(p.positionMs))?.title?.takeIf { it.isNotBlank() }
    val where = if (p.finished) "finished" else listOfNotNull(chapter, clock(p.positionMs)).joinToString(" · ")
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Newer position on another device") },
        text = { Text("“${book.title}” was last heard at $where. You're at ${clock(app.playback.position())}.") },
        confirmButton = { TextButton(onClick = { scope.launch { app.sync.acceptNewer() } }) { Text("Go there") } },
        dismissButton = { TextButton(onClick = { scope.launch { app.sync.declineNewer() } }) { Text("Stay here") } },
    )
}

/** On a server book's page: whether it's on the device, and the way to change that. */
@Composable
fun KeepOnDevice(app: App, book: Book) {
    if (!book.onServer) return
    val cache by app.remote.cache.collectAsState()
    val state = cache[book.id]
    val context = LocalContext.current
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
        when {
            state?.complete == true -> {
                Text("On this device · ${Formatter.formatShortFileSize(context, state.total)}",
                    style = MaterialTheme.typography.bodySmall, color = LocalTones.current.secondary)
                Spacer(Modifier.height(8.dp))
                Pill("Remove from device", Icons.Default.Delete, onClick = { app.remote.release(book) })
            }
            state?.kept == true -> {
                Text(
                    "Downloading ${(state.fraction * 100).toInt()}% of ${Formatter.formatShortFileSize(context, state.total)}",
                    style = MaterialTheme.typography.bodySmall, color = LocalTones.current.secondary,
                )
                Spacer(Modifier.height(6.dp))
                ProgressLine(state.fraction, height = 4.dp)
                state.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Pill("Cancel", Icons.Default.Close, onClick = { app.remote.release(book) })
            }
            else -> {
                Text("Streams from the server", style = MaterialTheme.typography.bodySmall, color = LocalTones.current.secondary)
                Spacer(Modifier.height(8.dp))
                Pill("Keep on device", Icons.Default.Download, onClick = { app.remote.keep(book) })
            }
        }
    }
}

/** On a cover: this book isn't on the device, and needs the server to play. */
@Composable
fun StreamMark(book: Book, modifier: Modifier = Modifier) {
    if (!book.onServer) return
    val cache by App.instance.remote.cache.collectAsState()
    if (cache[book.id]?.complete == true) return
    Box(
        modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Cloud, "On the server", Modifier.size(14.dp), tint = Color.White)
    }
}

/** Address and password. [address] may come from a scanned code, and is shown for checking. */
@Composable
fun ConnectDialog(app: App, address: String, onClose: () -> Unit) {
    var typed by remember(address) { mutableStateOf(address) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val base = ServerAddress.normalize(typed)
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Connect to server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    typed, { typed = it; error = null },
                    label = { Text("Address") },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true,
                    isError = typed.isNotBlank() && base == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    password, { password = it; error = null },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && base != null && password.isNotEmpty(), onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        app.sync.connect(base!!, password, Build.MODEL ?: "Android")
                        onClose()
                    } catch (e: IOException) {
                        error = e.message ?: "Server not reachable"
                    } catch (_: org.json.JSONException) {
                        error = "That doesn't look like a storiesd server"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Connecting…" else "Connect") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") } },
    )
}
