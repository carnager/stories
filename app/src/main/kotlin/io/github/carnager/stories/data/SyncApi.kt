package io.github.carnager.stories.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class RemotePosition(
    val path: String,
    val size: Long,
    val ms: Long,
    val finished: Boolean,
    val speed: Double,
    val lastPlayed: Long,
) {
    fun json(): JSONObject = JSONObject().put("path", path).put("size", size).put("ms", ms)
        .put("finished", finished).put("speed", speed).put("lastPlayed", lastPlayed)

    companion object {
        fun of(o: JSONObject) = RemotePosition(
            o.getString("path"), o.getLong("size"), o.getLong("ms"), o.optBoolean("finished"),
            o.optDouble("speed", 1.0), o.optLong("lastPlayed"),
        )
    }
}

data class RemoteBookmark(
    val id: String,
    val path: String,
    val size: Long,
    val ms: Long,
    val note: String,
    val created: Long,
    val updated: Long,
    val deleted: Boolean,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("path", path).put("size", size).put("ms", ms)
        .put("note", note).put("created", created).put("updated", updated).put("deleted", deleted)

    companion object {
        fun of(o: JSONObject) = RemoteBookmark(
            o.getString("id"), o.getString("path"), o.getLong("size"), o.getLong("ms"), o.optString("note"),
            o.optLong("created"), o.optLong("updated"), o.optBoolean("deleted"),
        )
    }
}

data class RemoteFile(val id: String, val path: String, val title: String, val size: Long, val durationMs: Long, val marks: List<Mark>) {
    fun json(): JSONObject = JSONObject().put("id", id).put("path", path).put("title", title).put("size", size)
        .put("durationMs", durationMs)
        .put("chapters", JSONArray().apply { marks.forEach { put(JSONObject().put("title", it.title).put("startMs", it.startMs)) } })

    companion object {
        fun of(o: JSONObject): RemoteFile {
            val a = o.optJSONArray("chapters")
            val marks = (0 until (a?.length() ?: 0)).map { i -> a!!.getJSONObject(i).let { Mark(it.optString("title"), it.getLong("startMs")) } }
            val path = o.getString("path")
            return RemoteFile(o.getString("id"), path, o.optString("title").ifEmpty { path.substringAfterLast('/').substringBeforeLast('.') },
                o.getLong("size"), o.optLong("durationMs"), marks)
        }
    }
}

data class RemoteBook(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val series: String,
    val year: String,
    val coverId: String,
    val files: List<RemoteFile>,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("author", author).put("narrator", narrator)
        .put("series", series).put("year", year).put("coverId", coverId)
        .put("files", JSONArray().apply { files.forEach { put(it.json()) } })

    companion object {
        fun of(o: JSONObject): RemoteBook {
            val a = o.getJSONArray("files")
            return RemoteBook(
                o.getString("id"), o.getString("title"), o.optString("author"), o.optString("narrator"), o.optString("series"),
                o.optString("year"), o.optString("coverId"), (0 until a.length()).map { RemoteFile.of(a.getJSONObject(it)) },
            )
        }
    }
}

/** The server said this device isn't signed in any more. */
class SignedOut : IOException("Signed out by the server")

class WrongPassword : IOException("Wrong password")

class TooManyAttempts : IOException("Too many attempts, try again in a minute")

class NotAllowed : IOException("This address needs https")

/** storiesd's API, one blocking call each: for the IO dispatcher. */
class SyncApi(private val base: String, private val token: String? = null) {
    data class Login(val token: String, val deviceId: String, val serverId: String)

    fun login(password: String, device: String): Login {
        val body = JSONObject().put("password", password).put("device", device)
        val o = JSONObject(request("POST", "/v1/login", body.toString()).body)
        return Login(o.getString("token"), o.getString("deviceId"), o.getString("serverId"))
    }

    /** The server's clock, for comparing times with it. */
    fun serverNow(): Long = JSONObject(request("GET", "/v1/server").body).getLong("now")

    /** The server's books, or null when unchanged since [etag]. */
    fun library(etag: String?): Pair<String?, List<RemoteBook>>? {
        val res = request("GET", "/v1/library", etag = etag)
        if (res.code == HttpURLConnection.HTTP_NOT_MODIFIED) return null
        val books = JSONObject(res.body).getJSONArray("books")
        return res.etag to (0 until books.length()).map { RemoteBook.of(books.getJSONObject(it)) }
    }

    fun positions(since: Long): Pair<Long, List<RemotePosition>> {
        val o = JSONObject(request("GET", "/v1/positions?since=$since").body)
        val a = o.getJSONArray("positions")
        return o.getLong("rev") to (0 until a.length()).map { RemotePosition.of(a.getJSONObject(it)) }
    }

    fun putPositions(now: Long, records: List<RemotePosition>) {
        val body = JSONObject().put("now", now).put("positions", JSONArray().apply { records.forEach { put(it.json()) } })
        request("PUT", "/v1/positions", body.toString())
    }

    fun bookmarks(since: Long): Pair<Long, List<RemoteBookmark>> {
        val o = JSONObject(request("GET", "/v1/bookmarks?since=$since").body)
        val a = o.getJSONArray("bookmarks")
        return o.getLong("rev") to (0 until a.length()).map { RemoteBookmark.of(a.getJSONObject(it)) }
    }

    fun putBookmarks(now: Long, records: List<RemoteBookmark>) {
        val body = JSONObject().put("now", now).put("bookmarks", JSONArray().apply { records.forEach { put(it.json()) } })
        request("PUT", "/v1/bookmarks", body.toString())
    }

    private class Response(val code: Int, val body: String, val etag: String?)

    private fun request(method: String, path: String, body: String? = null, etag: String? = null): Response {
        if (!ServerAddress.allowed(base)) throw NotAllowed()
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 5_000
            c.readTimeout = 15_000
            // A redirect could carry the token somewhere the address check never saw.
            c.instanceFollowRedirects = false
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            etag?.let { c.setRequestProperty("If-None-Match", it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            when {
                code == HttpURLConnection.HTTP_NOT_MODIFIED -> return Response(code, "", etag)
                code == HttpURLConnection.HTTP_UNAUTHORIZED -> throw if (token == null) WrongPassword() else SignedOut()
                code == 429 -> throw TooManyAttempts()
                code !in 200..299 -> throw IOException("Server answered $code")
            }
            return Response(code, c.inputStream.bufferedReader().use { it.readText() }, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }
}
