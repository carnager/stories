package io.github.carnager.stories.data

data class ServerFile(val path: String, val size: Long)

/** A file on the phone: its full path, size, and path below its library folder. */
data class LocalFile(val path: String, val size: Long, val relative: String)

/**
 * Which file on the phone is which on the server: the same path below the
 * library and the same size, or else the same name and size when exactly one
 * file on each side has them. Books don't come into it, so the phone and the
 * server may group the same files differently.
 */
class FileMatch(server: List<ServerFile>, local: List<LocalFile>) {
    private val toServer = HashMap<String, ServerFile>()
    private val toLocal = HashMap<ServerFile, String>()

    init {
        val known = server.toHashSet()
        val rest = ArrayList<LocalFile>()
        for (f in local) {
            val same = ServerFile(f.relative, f.size)
            if (same in known && same !in toLocal) link(f.path, same) else rest += f
        }
        fun key(name: String, size: Long) = name.substringAfterLast('/') to size
        val serverByName = server.filter { it !in toLocal }.groupBy { key(it.path, it.size) }
        val localByName = rest.groupBy { key(it.path, it.size) }
        for ((k, files) in localByName) {
            val only = files.singleOrNull() ?: continue
            val match = serverByName[k]?.singleOrNull() ?: continue
            link(only.path, match)
        }
    }

    private fun link(local: String, server: ServerFile) {
        toServer[local] = server
        toLocal[server] = local
    }

    fun server(localPath: String): ServerFile? = toServer[localPath]
    fun local(file: ServerFile): String? = toLocal[file]
}
