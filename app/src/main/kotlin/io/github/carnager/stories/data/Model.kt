package io.github.carnager.stories.data

/** A chapter inside one file, from its own chapter marks. */
data class Mark(val title: String, val startMs: Long)

/** One audio file of a book, in playing order. */
data class Track(
    val path: String,
    val title: String,
    val durationMs: Long,
    val marks: List<Mark> = emptyList(),
)

/**
 * A chapter as the player shows it: the file's own marks where it has
 * them, otherwise the file itself. Times are on the whole book's clock.
 */
data class Chapter(
    val title: String,
    val trackIndex: Int,
    val startInTrackMs: Long,
    val startMs: Long,
    val durationMs: Long,
)

/**
 * A book: one folder's files, or the files in it that share an album, or
 * one file with chapters. [folder] is where it lives, for the folder view.
 */
data class Book(
    val id: String,
    val folder: String,
    val title: String,
    val author: String,
    val narrator: String,
    val year: String,
    val series: String,
    val tracks: List<Track>,
    val cover: String?,
    val fingerprint: String,
) {
    val durationMs: Long = tracks.sumOf { it.durationMs.coerceAtLeast(0) }

    /** Where each file starts on the book's clock. */
    val trackStarts: LongArray = LongArray(tracks.size).also { starts ->
        var at = 0L
        tracks.forEachIndexed { i, track -> starts[i] = at; at += track.durationMs.coerceAtLeast(0) }
    }

    val chapters: List<Chapter> = buildList {
        tracks.forEachIndexed { i, track ->
            val start = trackStarts[i]
            if (track.marks.size > 1 || (track.marks.size == 1 && tracks.size == 1)) {
                track.marks.forEachIndexed { m, mark ->
                    val end = track.marks.getOrNull(m + 1)?.startMs ?: track.durationMs
                    add(Chapter(mark.title, i, mark.startMs, start + mark.startMs, (end - mark.startMs).coerceAtLeast(0)))
                }
            } else {
                add(Chapter(track.title, i, 0, start, track.durationMs.coerceAtLeast(0)))
            }
        }
    }

    fun chapterAt(bookMs: Long): Int {
        if (chapters.isEmpty()) return -1
        var lo = 0
        var hi = chapters.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chapters[mid].startMs <= bookMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The file and the offset in it for a time on the book's clock. */
    fun locate(bookMs: Long): Pair<Int, Long> {
        if (tracks.isEmpty()) return 0 to 0L
        val clamped = bookMs.coerceIn(0, durationMs)
        var index = trackStarts.indexOfLast { it <= clamped }.coerceAtLeast(0)
        // A file of no length can't hold a position; the next one does.
        while (index < tracks.lastIndex && tracks[index].durationMs <= 0) index++
        return index to (clamped - trackStarts[index]).coerceAtLeast(0)
    }
}

/** How far a book has been heard. Kept by book id, and found again by fingerprint after a move. */
data class Progress(
    val bookId: String,
    val fingerprint: String,
    val trackIndex: Int,
    val trackPositionMs: Long,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val speed: Float,
    val lastPlayed: Long,
) {
    val started: Boolean get() = finished || positionMs > 5_000
    val fraction: Float
        get() = when {
            finished -> 1f
            durationMs <= 0 -> 0f
            else -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
}

enum class Status { New, Listening, Finished }

fun Progress?.status(): Status = when {
    this == null || !started -> Status.New
    finished -> Status.Finished
    else -> Status.Listening
}

data class Bookmark(val id: Long, val bookId: String, val positionMs: Long, val note: String, val created: Long)
