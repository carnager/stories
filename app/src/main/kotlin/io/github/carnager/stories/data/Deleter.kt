package io.github.carnager.stories.data

import org.json.JSONObject
import java.io.File

/**
 * Takes books off the phone: their audio files, then what came with them
 * -- covers, CUE sheets, playlists, the position files -- once no audio is
 * left beside it, then folders left empty, up to but not including the
 * chosen folder. A folder holding anything else is left as it is.
 */
object Deleter {
    /** Files that belong to the audio beside them, and go with the last of it. */
    private val companions = setOf("jpg", "jpeg", "png", "webp", "gif", "cue", "m3u", "m3u8", "nfo", "sfv")
    private val companionNames = setOf(FolderPositions.FILE_NAME, SabpReader.FILE_NAME, "${FolderPositions.FILE_NAME}.tmp")

    data class Result(val deleted: Int, val bytes: Long, val failed: List<String>)

    /** What deleting these books would take: files and bytes, for the question before. */
    fun measure(books: List<Book>): Pair<Int, Long> {
        val files = books.flatMap { it.tracks }.map { File(it.path) }.filter { it.isFile }
        return files.size to files.sumOf { it.length() }
    }

    fun delete(books: List<Book>, roots: List<String>): Result {
        val rootSet = roots.map { File(it).absoluteFile.path.trimEnd('/') }.toSet()
        var deleted = 0
        var bytes = 0L
        val failed = ArrayList<String>()
        val folders = HashSet<File>()

        for (book in books) {
            for (track in book.tracks) {
                val file = File(track.path)
                if (!file.exists()) continue
                val size = file.length()
                if (file.delete()) { deleted++; bytes += size } else failed += file.path
                file.parentFile?.let(folders::add)
            }
            // Its own entry in a position file kept for synced folders.
            forget(File(book.folder, FolderPositions.FILE_NAME), book.fingerprint)
            folders += File(book.folder)
        }

        // Deepest first, so a disc folder goes before the book folder above it.
        for (folder in folders.sortedByDescending { it.path.length }) {
            tidy(folder, rootSet)
        }
        return Result(deleted, bytes, failed)
    }

    /** Without audio left, a folder's companions go; empty, the folder goes, and so on upward. */
    private fun tidy(start: File, roots: Set<String>) {
        var folder: File? = start
        while (folder != null && folder.isDirectory && folder.absoluteFile.path.trimEnd('/') !in roots) {
            val inside = folder.listFiles().orEmpty()
            val hasAudio = inside.any { it.isFile && it.extension.lowercase() in Scanner.audio }
            if (!hasAudio) {
                inside.filter { it.isFile && (it.extension.lowercase() in companions || it.name in companionNames) }.forEach { it.delete() }
            }
            if (!folder.listFiles().isNullOrEmpty()) return
            if (!folder.delete()) return
            folder = folder.parentFile
        }
    }

    private fun forget(file: File, fingerprint: String) {
        if (!file.isFile) return
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val books = root.optJSONObject("books") ?: return
        books.remove(fingerprint)
        if (books.length() == 0) file.delete() else Store.writeAtomically(file, root.toString(2))
    }
}
