package io.github.carnager.stories.data

import java.security.MessageDigest

/** What the scanner knows of one audio file. */
data class FileTags(
    val path: String,
    val size: Long,
    val modified: Long,
    val title: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val artist: String = "",
    val composer: String = "",
    val year: String = "",
    val track: Int = 0,
    val disc: Int = 0,
    val durationMs: Long = 0,
    val hasPicture: Boolean = false,
    val marks: List<Mark> = emptyList(),
) {
    val name: String get() = path.substringAfterLast('/')
    val dir: String get() = path.substringBeforeLast('/')
}

/** A book before its cover is found. */
data class Draft(val folder: String, val key: String, val files: List<FileTags>) {
    /** Names the book for good: its id is made from this, and the overrides refer to it. */
    val id: String get() = "$folder#$key"
}

/** Details set by hand, over what the tags say. */
data class Edit(val title: String? = null, val author: String? = null, val narrator: String? = null, val series: String? = null)

/**
 * Corrections made by hand, kept by the app and never written to the files:
 * books joined into one, books cut into one per file, disc folders kept
 * apart, and details edited.
 */
data class Overrides(
    /** Each joined book's key, with the books (draft ids) it was made of. */
    val merges: Map<String, Set<String>> = emptyMap(),
    /** Books (draft ids) to be one book per file. */
    val perFile: Set<String> = emptySet(),
    /** Folders whose disc folders stay books of their own. */
    val keepApart: Set<String> = emptySet(),
    /** By book id. */
    val edits: Map<String, Edit> = emptyMap(),
)

/**
 * Turns files into books. A folder of files is one book -- the way a
 * library of folders is laid out, a series being a folder of such books.
 * Tags refine that: files in one folder with different albums are
 * different books, files in sibling disc folders (CD1, CD2, ...) with the
 * same album are one, and single files with chapters stand alone.
 */
object Grouping {
    private val discFolder = Regex("""^(cd|disc|disk|part|teil|tape|kassette|seite|side)[\s._-]*\d+.*$""", RegexOption.IGNORE_CASE)
    private val standalone = setOf("m4b")

    fun group(files: List<FileTags>, overrides: Overrides = Overrides()): List<Draft> {
        val byDir = files.groupBy { it.dir }
        val drafts = ArrayList<Draft>()
        for ((dir, inDir) in byDir) drafts += splitFolder(dir, inDir)
        val automatic = mergeDiscs(drafts, overrides.keepApart)
        return join(cut(automatic, overrides.perFile), overrides.merges).map { it.copy(files = sortTracks(it.files)) }
    }

    /** Books asked to be one per file become so; each keeps its folder. */
    private fun cut(drafts: List<Draft>, perFile: Set<String>): List<Draft> = drafts.flatMap { draft ->
        if (draft.id !in perFile || draft.files.size < 2) listOf(draft)
        else draft.files.map { f -> Draft(draft.folder, "file:" + f.path.removePrefix(draft.folder + "/"), listOf(f)) }
    }

    /** Books joined by hand: one book, in the folder they all sit in. */
    private fun join(drafts: List<Draft>, merges: Map<String, Set<String>>): List<Draft> {
        if (merges.isEmpty()) return drafts
        val byId = drafts.associateBy { it.id }
        val used = HashSet<String>()
        val result = ArrayList<Draft>()
        for ((key, ids) in merges) {
            val parts = ids.mapNotNull { byId[it] }.filter { it.id !in used }
            if (parts.size < 2) continue
            parts.forEach { used += it.id }
            result += Draft(commonFolder(parts.map { it.folder }), key, parts.flatMap { it.files })
        }
        result += drafts.filter { it.id !in used }
        return result
    }

    fun commonFolder(folders: List<String>): String {
        var common = folders.first()
        for (f in folders) {
            while (f != common && !f.startsWith("$common/")) common = common.substringBeforeLast('/', "")
        }
        return common
    }

    /** The key a hand-made join of these books gets: the same for the same books. */
    fun mergeKey(ids: Collection<String>) = "merge:" + hash(ids.sorted().joinToString("|"))

    private fun splitFolder(dir: String, files: List<FileTags>): List<Draft> {
        // Several .m4b in one folder are several books, unless they share an album.
        val (single, rest) = files.partition { it.name.substringAfterLast('.').lowercase() in standalone }
        val drafts = ArrayList<Draft>()
        val singleByAlbum = single.groupBy { normal(it.album) }
        for ((album, group) in singleByAlbum) {
            if (album.isNotEmpty() && group.size > 1) drafts += Draft(dir, album, group)
            else group.forEach { drafts += Draft(dir, "file:" + it.name, listOf(it)) }
        }
        if (rest.isNotEmpty()) {
            val albums = rest.map { normal(it.album) }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
            // A folder only splits when its albums are real groups, not a stray tag or two.
            val real = albums.filter { it.value >= 2 }
            if (real.size >= 2) {
                val byAlbum = rest.groupBy { normal(it.album).takeIf { a -> a in real } ?: "" }
                byAlbum.forEach { (album, group) -> drafts += Draft(dir, album, group) }
            } else {
                drafts += Draft(dir, "", rest)
            }
        }
        return drafts
    }

    /** Sibling folders named like discs, or tagged as discs of one album, become one book in their parent. */
    private fun mergeDiscs(drafts: List<Draft>, keepApart: Set<String>): List<Draft> {
        val candidates = drafts.filter { it.key == "" || !it.key.startsWith("file:") }
            .groupBy { it.folder.substringBeforeLast('/') }
        val merged = HashSet<Draft>()
        val result = ArrayList<Draft>()
        val atFolder = drafts.groupBy { it.folder }
        for ((parent, siblings) in candidates) {
            if (siblings.size < 2 || parent in keepApart) continue
            // The parent's own files would make it a book of its own; keep apart then.
            if (atFolder[parent] != null) continue
            val named = siblings.filter { discFolder.matches(it.folder.substringAfterLast('/')) }
            val byAlbum = siblings.groupBy { albumOf(it) }.filterKeys { it.isNotEmpty() }
            val groups = ArrayList<List<Draft>>()
            if (named.size >= 2 && named.size == siblings.size) {
                groups += named
            } else {
                byAlbum.values.filter { group ->
                    group.size >= 2 && group.flatMap { it.files }.map { it.disc }.toSet().size >= 2 &&
                        group.all { d -> d.files.map { it.disc }.toSet().size == 1 }
                }.forEach(groups::add)
            }
            for (group in groups) {
                merged += group
                result += Draft(parent, albumOf(group.first()), group.flatMap { draft ->
                    // Disc order from the folder when tags don't give one.
                    val folderDisc = Regex("""\d+""").find(draft.folder.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0
                    draft.files.map { if (it.disc == 0) it.copy(disc = folderDisc) else it }
                })
            }
        }
        result += drafts.filter { it !in merged }
        return result
    }

    private fun albumOf(draft: Draft): String =
        draft.files.map { normal(it.album) }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()

    fun sortTracks(files: List<FileTags>): List<FileTags> {
        val useTracks = files.size > 1 && files.all { it.track > 0 } && files.map { it.disc to it.track }.toSet().size == files.size
        return if (useTracks) files.sortedWith(compareBy<FileTags> { it.disc }.thenBy { it.track }.thenComparator { a, b -> NaturalOrder.compare(a.path, b.path) })
        else files.sortedWith { a, b -> NaturalOrder.compare(a.path, b.path) }
    }

    private fun normal(s: String) = s.trim().lowercase()

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** The same files, wherever they are: how progress finds a book that moved. */
    fun fingerprint(files: List<FileTags>): String = hash(files.map { "${it.name}:${it.size}" }.sorted().joinToString("|"))

    /** The book's name: its album when its files agree on one, else its folder's or file's name. */
    fun title(draft: Draft, folderName: String): String {
        // Joined by hand: named after its first part.
        if (draft.key.startsWith("merge:")) {
            val first = draft.files.first()
            return first.album.trim().ifEmpty { first.dir.substringAfterLast('/') }
        }
        val albums = draft.files.map { it.album.trim() }.filter { it.isNotEmpty() }
        val album = albums.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (album != null && album.value * 2 > draft.files.size) return album.key
        if (draft.key.startsWith("file:")) {
            val file = draft.files.first()
            return file.title.ifBlank { file.name.substringBeforeLast('.') }
        }
        return folderName
    }

    fun author(files: List<FileTags>): String {
        fun most(pick: (FileTags) -> String) =
            files.map { pick(it).trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return most { it.albumArtist } ?: most { it.artist } ?: ""
    }

    fun narrator(files: List<FileTags>): String =
        files.map { it.composer.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
}

/** "2" before "10", case aside: how people number files and folders. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
