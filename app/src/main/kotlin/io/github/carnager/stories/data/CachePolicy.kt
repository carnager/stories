package io.github.carnager.stories.data

/**
 * What may be taken off the device, decided apart from doing it. Only copies
 * are ever concerned, and a book being heard or still to be heard is never
 * removed to make room.
 */
object CachePolicy {
    /** How long a copy outlives its file's absence from the server: a disk may just be unmounted. */
    const val GRACE_MS = 7 * 24 * 3600_000L

    data class CachedBook(val id: String, val bytes: Long, val finished: Boolean, val lastPlayed: Long, val loaded: Boolean)

    data class Plan(val evict: List<String>, val fits: Boolean)

    /**
     * Which finished books to remove, longest ago first, so that [needed]
     * more bytes fit under [limit]. If they can't, nothing is removed for
     * nothing, unless [partial] asks to get as close as possible.
     */
    fun plan(books: List<CachedBook>, limit: Long, needed: Long, target: String? = null, partial: Boolean = false): Plan {
        var used = books.sumOf { it.bytes }
        if (used + needed <= limit) return Plan(emptyList(), true)
        val evict = ArrayList<String>()
        for (b in books.filter { it.finished && !it.loaded && it.id != target }.sortedBy { it.lastPlayed }) {
            if (used + needed <= limit) break
            evict += b.id
            used -= b.bytes
        }
        val fits = used + needed <= limit
        return if (fits || partial) Plan(evict, fits) else Plan(emptyList(), false)
    }

    data class Sweep(val delete: Set<String>, val waiting: Map<String, Long>)

    /**
     * Which copies no longer belong to a file on the server. One whose path
     * now holds a different file was replaced and goes at once; one whose
     * file is simply gone waits [GRACE_MS]. An empty library proves nothing,
     * and a copy the player may still be reading stays.
     *
     * @param onDisk file ids with a copy
     * @param paths the path each copy was downloaded from
     * @param server the server's file ids, with their paths
     * @param waiting since when each missing copy has been missing
     * @param inUse paths of the book in the player
     */
    fun sweep(
        onDisk: Set<String>,
        paths: Map<String, String>,
        server: Map<String, String>,
        waiting: Map<String, Long>,
        inUse: Set<String>,
        now: Long,
    ): Sweep {
        if (server.isEmpty()) return Sweep(emptySet(), waiting)
        val current = server.values.toHashSet()
        val delete = HashSet<String>()
        val still = HashMap<String, Long>()
        for (id in onDisk) {
            if (id in server) continue
            val path = paths[id]
            val since = waiting[id] ?: now
            when {
                path != null && path in inUse -> still[id] = since
                path != null && path in current -> delete += id
                now - since >= GRACE_MS -> delete += id
                else -> still[id] = since
            }
        }
        return Sweep(delete, still)
    }
}
