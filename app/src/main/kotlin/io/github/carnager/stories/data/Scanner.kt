package io.github.carnager.stories.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds the books under the chosen folders. Tags are read once per file and
 * kept, keyed by path, size and time, so a rescan only reads what changed.
 */
class Scanner(private val dir: File) {
    private val cacheFile = File(dir, "tags.json")
    private val coverDir = File(dir, "covers").apply { mkdirs() }

    data class Progress(val done: Int, val total: Int)

    suspend fun scan(roots: List<String>, onProgress: (Progress) -> Unit): List<Book> = withContext(Dispatchers.IO) {
        val cache = loadCache()
        val walked = roots.flatMap { root -> walk(File(root)) }
        val found = walked.filter { it.extension.lowercase() in audio }
        val sheets = walked.filter { it.extension.lowercase() == "cue" }
        val done = AtomicInteger()
        val total = found.size
        val fresh = found.filter { file ->
            val cached = cache[file.path]
            cached == null || cached.size != file.length() || cached.modified != file.lastModified()
        }
        onProgress(Progress(total - fresh.size, total))
        done.set(total - fresh.size)
        val reads = Dispatchers.IO.limitedParallelism(4)
        val read = coroutineScope {
            fresh.chunked(16).map { chunk ->
                async(reads) {
                    chunk.map { file ->
                        readTags(file).also { onProgress(Progress(done.incrementAndGet(), total)) }
                    }
                }
            }.awaitAll().flatten()
        }
        read.forEach { cache[it.path] = it }
        val present = found.mapTo(HashSet()) { it.path }
        cache.keys.retainAll(present)
        saveCache(cache)
        // A CUE sheet beside a file names its chapters, over any the file has itself.
        val audioByDir = found.groupBy({ it.parent.orEmpty() }, { it.path })
        val cued = HashMap<String, List<Mark>>()
        sheets.forEach { sheet -> cued += Cue.match(Cue.read(sheet), audioByDir[sheet.parent].orEmpty()) }
        val tags = found.mapNotNull { f -> cache[f.path]?.let { t -> cued[f.path]?.let { t.copy(marks = it) } ?: t } }
        build(roots, Grouping.group(tags))
    }

    private fun walk(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { !it.name.startsWith(".") || it == root }
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase().let { e -> e in audio || e == "cue" } }
            .toList()
    }

    private fun readTags(file: File): FileTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            fun key(k: Int) = retriever.extractMetadata(k)?.trim().orEmpty()
            FileTags(
                path = file.path,
                size = file.length(),
                modified = file.lastModified(),
                title = key(MediaMetadataRetriever.METADATA_KEY_TITLE),
                album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                composer = key(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                year = key(MediaMetadataRetriever.METADATA_KEY_YEAR).ifEmpty { key(MediaMetadataRetriever.METADATA_KEY_DATE).take(4) },
                track = number(key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                disc = number(key(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0,
                hasPicture = retriever.embeddedPicture != null,
                marks = if (file.extension.lowercase() in chaptered) ChapterReader.read(file) else emptyList(),
            )
        } catch (_: Exception) {
            FileTags(file.path, file.length(), file.lastModified())
        } finally {
            retriever.release()
        }
    }

    /** "3/12" and "03" are both 3. */
    private fun number(s: String) = s.substringBefore('/').trim().toIntOrNull() ?: 0

    private fun build(roots: List<String>, drafts: List<Draft>): List<Book> {
        val rootSet = roots.map { it.trimEnd('/') }.toSet()
        // A folder whose subfolders are several books is a series, named after it.
        val booksPerParent = drafts.groupingBy { it.folder.substringBeforeLast('/') }.eachCount()
        return drafts.map { draft ->
            val folderName = draft.folder.substringAfterLast('/')
            val parent = draft.folder.substringBeforeLast('/')
            val id = Grouping.hash(draft.folder + "#" + draft.key)
            val series = if (parent !in rootSet && (booksPerParent[parent] ?: 0) >= 2 && draft.folder !in rootSet) parent.substringAfterLast('/') else ""
            Book(
                id = id,
                folder = draft.folder,
                title = Grouping.title(draft, folderName),
                author = Grouping.author(draft.files),
                narrator = Grouping.narrator(draft.files),
                year = draft.files.firstOrNull { it.year.isNotEmpty() }?.year.orEmpty(),
                series = series,
                tracks = draft.files.map { f ->
                    Track(f.path, f.title.ifBlank { f.name.substringBeforeLast('.') }, f.durationMs, f.marks)
                },
                cover = cover(id, draft),
                fingerprint = Grouping.fingerprint(draft.files),
            )
        }
    }

    /**
     * The book's picture: an image in its folder (when the folder is its
     * alone), else the first embedded one, saved small once.
     */
    private fun cover(id: String, draft: Draft): String? {
        val saved = File(coverDir, "$id.jpg")
        if (draft.key.isEmpty()) {
            val images = File(draft.folder).listFiles { f -> f.isFile && f.extension.lowercase() in pictures }.orEmpty()
            val preferred = images.sortedWith(compareBy<File> { f ->
                val name = f.nameWithoutExtension.lowercase()
                preferredNames.indexOfFirst { name.startsWith(it) }.let { if (it < 0) preferredNames.size else it }
            }.thenBy { it.name })
            preferred.firstOrNull()?.let { image ->
                if (!saved.exists() || saved.lastModified() < image.lastModified()) shrink(BitmapFactory.decodeFile(image.path, sampled(image.path)), saved)
                if (saved.exists()) return saved.path
            }
        }
        if (saved.exists()) return saved.path
        val withPicture = draft.files.firstOrNull { it.hasPicture } ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(withPicture.path)
            val bytes = retriever.embeddedPicture ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.inSampleSize = sample(options.outWidth, options.outHeight)
            options.inJustDecodeBounds = false
            shrink(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options), saved)
            saved.path.takeIf { saved.exists() }
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun sampled(path: String) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, this)
        inSampleSize = sample(outWidth, outHeight)
        inJustDecodeBounds = false
    }

    private fun sample(w: Int, h: Int): Int {
        var s = 1
        while (w / (s * 2) >= COVER && h / (s * 2) >= COVER) s *= 2
        return s
    }

    private fun shrink(bitmap: Bitmap?, to: File) {
        bitmap ?: return
        val scale = COVER.toFloat() / maxOf(bitmap.width, bitmap.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val tmp = File(to.path + ".tmp")
        tmp.outputStream().use { sized.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        tmp.renameTo(to)
    }

    private fun loadCache(): HashMap<String, FileTags> {
        val map = HashMap<String, FileTags>()
        if (!cacheFile.exists()) return map
        runCatching {
            val array = JSONArray(cacheFile.readText())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val marks = o.optJSONArray("marks")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it).let { m -> Mark(m.getString("t"), m.getLong("s")) } }
                }.orEmpty()
                val tags = FileTags(
                    path = o.getString("path"), size = o.getLong("size"), modified = o.getLong("mod"),
                    title = o.optString("title"), album = o.optString("album"), albumArtist = o.optString("aa"),
                    artist = o.optString("artist"), composer = o.optString("comp"), year = o.optString("year"),
                    track = o.optInt("track"), disc = o.optInt("disc"), durationMs = o.optLong("dur"),
                    hasPicture = o.optBoolean("pic"), marks = marks,
                )
                map[tags.path] = tags
            }
        }
        return map
    }

    private fun saveCache(cache: Map<String, FileTags>) {
        val array = JSONArray()
        cache.values.forEach { t ->
            array.put(JSONObject().apply {
                put("path", t.path); put("size", t.size); put("mod", t.modified)
                if (t.title.isNotEmpty()) put("title", t.title)
                if (t.album.isNotEmpty()) put("album", t.album)
                if (t.albumArtist.isNotEmpty()) put("aa", t.albumArtist)
                if (t.artist.isNotEmpty()) put("artist", t.artist)
                if (t.composer.isNotEmpty()) put("comp", t.composer)
                if (t.year.isNotEmpty()) put("year", t.year)
                if (t.track != 0) put("track", t.track)
                if (t.disc != 0) put("disc", t.disc)
                put("dur", t.durationMs)
                if (t.hasPicture) put("pic", true)
                if (t.marks.isNotEmpty()) put("marks", JSONArray().apply {
                    t.marks.forEach { put(JSONObject().put("t", it.title).put("s", it.startMs)) }
                })
            })
        }
        Store.writeAtomically(cacheFile, array.toString())
    }

    companion object {
        const val COVER = 640
        val audio = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "mka", "webm", "mp4")
        private val chaptered = setOf("mp3", "m4a", "m4b", "mp4", "aac", "flac", "ogg", "oga", "opus")
        private val pictures = setOf("jpg", "jpeg", "png", "webp")
        private val preferredNames = listOf("cover", "folder", "front", "album", "book")
    }
}
