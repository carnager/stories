package io.github.carnager.stories.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.carnager.stories.App
import java.io.File

/** A folder picked in the system's picker, as the path it is on the phone. */
fun treeToPath(uri: Uri): String? {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val volume = id.substringBefore(':')
    val rest = id.substringAfter(':', "")
    val base = if (volume == "primary") Environment.getExternalStorageDirectory().path else "/storage/$volume"
    return if (rest.isEmpty()) base else "$base/$rest"
}

fun hasStorage(): Boolean = Environment.isExternalStorageManager()

/** Asks for the folders to be readable: Android's all-files access, for this app. */
fun askStorage(context: android.content.Context) {
    val intent = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

@Composable
fun SettingsScreen(app: App, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val settings by app.settings.state.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let(::treeToPath) ?: return@rememberLauncherForActivityResult
        if (path !in settings.roots) {
            app.settings.update { it.copy(roots = it.roots + path) }
            app.library.rescan()
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Close") }
                Box(Modifier.weight(1f)) { ScreenHeader("Settings") }
            }

            SectionLabel("Audiobook folders")
            settings.roots.forEach { root ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(root.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge)
                        Text(root, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = {
                        app.settings.update { it.copy(roots = it.roots - root) }
                        app.library.rescan()
                    }) { Icon(Icons.Default.Close, "Remove", tint = LocalTones.current.muted) }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Add a folder", Icons.Default.Add, onClick = { pick.launch(null) })
                Pill("Look again", null, onClick = app.library::rescan)
            }
            if (!hasStorage()) {
                val context = LocalContext.current
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Pill("Allow reading files", Icons.Default.Lock, onClick = { askStorage(context) },
                        container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            SectionLabel("Skipping")
            SkipSetting("Back", "Inner left button, and headphones' previous", settings.rewindSeconds, listOf(5, 10, 15, 20, 30, 45, 60), chapters = false) { v ->
                app.settings.update { it.copy(rewindSeconds = v) }
            }
            SkipSetting("Forward", "Inner right button, and headphones' next", settings.forwardSeconds, listOf(10, 15, 20, 30, 45, 60, 90, 120), chapters = false) { v ->
                app.settings.update { it.copy(forwardSeconds = v) }
            }
            SkipSetting("Outer buttons", "Skip a chapter, or a longer jump", settings.outerSkipSeconds, listOf(60, 120, 300, 600, 900), chapters = true) { v ->
                app.settings.update { it.copy(outerSkipSeconds = v) }
            }
            Toggle("Step back after a pause", "A few seconds after a short pause, up to half a minute after a long one", settings.smartRewind) { v ->
                app.settings.update { it.copy(smartRewind = v) }
            }
            Toggle("Skip silence", "Leaves out long quiet stretches", settings.skipSilence) { v -> app.settings.update { it.copy(skipSilence = v) } }

            SectionLabel("Sleep")
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text("Fade out", style = MaterialTheme.typography.bodyLarge)
                Text("How long it grows quieter before it stops", style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 30, 60, 120, 300).forEach { v ->
                        Choice(skipLabel(v), settings.sleepFadeSeconds == v) { app.settings.update { it.copy(sleepFadeSeconds = v) } }
                    }
                }
            }
            Toggle("Shake to start over", "Also brings it back for a minute after it stopped", settings.shakeToExtend) { v ->
                app.settings.update { it.copy(shakeToExtend = v) }
            }

            SectionLabel("Look")
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("System", settings.theme == "system") { app.settings.update { it.copy(theme = "system") } }
                Choice("Dark", settings.theme == "dark") { app.settings.update { it.copy(theme = "dark") } }
                Choice("Light", settings.theme == "light") { app.settings.update { it.copy(theme = "light") } }
            }
            Toggle("Wallpaper colours", "Material You, instead of the lamp and paper", settings.dynamicColor) { v -> app.settings.update { it.copy(dynamicColor = v) } }
            Toggle("Covers in a grid", "Otherwise a list with small covers", settings.grid) { v -> app.settings.update { it.copy(grid = v) } }

            val widgets = LocalContext.current.getSystemService(android.appwidget.AppWidgetManager::class.java)
            if (widgets?.isRequestPinAppWidgetSupported == true) {
                val context = LocalContext.current
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Pill("Add widget to home screen", Icons.Default.Add, onClick = {
                        widgets.requestPinAppWidget(android.content.ComponentName(context, io.github.carnager.stories.Widget::class.java), null, null)
                    })
                }
            }

            SectionLabel("Your listening")
            Toggle(
                "Keep places in the book folders",
                "A small file beside each book, so a synced copy of the folders (Syncthing, Nextcloud) carries where you were to your other devices",
                settings.shareInFolders,
            ) { v -> app.settings.update { it.copy(shareInFolders = v) } }
            val context = LocalContext.current
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Pill("Save a copy", null, onClick = {
                    val file = File(context.getExternalFilesDir(null), "stories-progress.json")
                    file.writeText(app.library.export())
                    android.widget.Toast.makeText(context, "Saved to ${file.path}", android.widget.Toast.LENGTH_LONG).show()
                })
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}

/** The first start: allow reading files, then point at the audiobooks. */
@Composable
fun SetupScreen(app: App, storageGranted: Boolean) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let(::treeToPath) ?: return@rememberLauncherForActivityResult
        app.settings.update { it.copy(roots = listOf(path)) }
        app.library.rescan()
    }
    val storage = Environment.getExternalStorageDirectory()
    val found = listOf("Audiobooks", "Hörbücher", "Hoerbuecher", "Hörspiele", "Books", "Audiobook")
        .map { File(storage, it) }.filter { storageGranted && it.isDirectory }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Stories", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your audiobooks, from their folders. Each folder is a book, a folder of them a series, and every one remembers where you were.",
                style = MaterialTheme.typography.bodyLarge, color = LocalTones.current.secondary,
            )
            Spacer(Modifier.height(36.dp))
            Step(1, "Let Stories read your files", done = storageGranted) {
                Pill("Allow", Icons.Default.Lock, onClick = { askStorage(context) },
                    container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(24.dp))
            Step(2, "Show it where your audiobooks are", done = false) {
                if (storageGranted) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        found.forEach { dir ->
                            Pill(dir.name, Icons.Default.Folder, onClick = {
                                app.settings.update { it.copy(roots = listOf(dir.path)) }
                                app.library.rescan()
                            }, container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                        }
                        Pill(if (found.isEmpty()) "Choose a folder" else "Another folder", Icons.Default.Add, onClick = { pick.launch(null) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, done: Boolean, action: @Composable () -> Unit) {
    Row {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(50))
                .background(if (done) LocalTones.current.done else LocalTones.current.raised),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (done) "✓" else "$number", style = MaterialTheme.typography.labelLarge,
                color = if (done) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
            if (!done) action()
        }
    }
}

/** "45", "45s", "2m", "2 min", "1:30": a skip's length typed, in seconds. */
fun parseSkip(text: String): Int? {
    val t = text.trim().lowercase().replace(" ", "")
    Regex("^(\\d+):(\\d{1,2})$").find(t)?.let { m -> return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() }
    Regex("^(\\d+)(m|min)$").find(t)?.let { m -> return m.groupValues[1].toInt() * 60 }
    Regex("^(\\d+)(s|sec)?$").find(t)?.let { m -> return m.groupValues[1].toInt() }
    return null
}

/** A skip length to set: the usual ones to tap, or any typed in. For the outer buttons, chapters too. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SkipSetting(title: String, detail: String, current: Int, presets: List<Int>, chapters: Boolean, onChange: (Int) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (current == 0) "Chapter" else skipLabel(current),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    if (!open) return
    var typed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(if (current == 0) "" else shortSpan(current)) }
    val parsed = parseSkip(typed)?.takeIf { it in 1..3600 }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (chapters) Choice("Chapter", current == 0) { onChange(0); open = false }
                    presets.forEach { v -> Choice(skipLabel(v), current == v) { onChange(v); open = false } }
                }
                androidx.compose.material3.OutlinedTextField(
                    typed, { typed = it },
                    label = { Text("Or any length") },
                    placeholder = { Text("45, 2m, 1:30") },
                    singleLine = true,
                    isError = typed.isNotBlank() && parsed == null,
                    supportingText = { if (parsed != null) Text(skipLabel(parsed)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = parsed != null, onClick = { parsed?.let(onChange); open = false }) { Text("Set") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}
