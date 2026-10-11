package io.github.carnager.stories.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Chapter marks from a file's own tags, which Android's metadata reader
 * doesn't give: MP4's Nero list (`chpl`) or QuickTime chapter track,
 * ID3v2 `CHAP` frames, and the `CHAPTER001=` comments of FLAC, Ogg Vorbis
 * and Opus. Reads only the boxes and frames it needs.
 */
object ChapterReader {
    fun read(file: File): List<Mark> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            if (raf.length() < 12) return emptyList()
            raf.readFully(head)
            when {
                head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> id3(raf)
                String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4(raf)
                String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> flac(raf)
                String(head, 0, 4, Charsets.ISO_8859_1) == "OggS" -> ogg(raf)
                else -> emptyList()
            }
        }
    }.getOrDefault(emptyList()).sortedBy { it.startMs }.distinctBy { it.startMs }

    // --- ID3v2 ---

    private fun id3(raf: RandomAccessFile): List<Mark> {
        raf.seek(0)
        val header = ByteArray(10).also(raf::readFully)
        val version = header[3].toInt()
        if (version !in 3..4) return emptyList()
        val size = syncsafe(header, 6)
        val body = ByteArray(size.coerceAtMost(raf.length().toInt() - 10)).also(raf::readFully)
        var at = 0
        // An extended header, rare, comes before the frames.
        if (header[5].toInt() and 0x40 != 0) {
            at += if (version == 4) syncsafe(body, 0) else int32(body, 0) + 4
        }
        val marks = ArrayList<Mark>()
        while (at + 10 <= body.size) {
            val id = String(body, at, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize = if (version == 4) syncsafe(body, at + 4) else int32(body, at + 4)
            if (frameSize <= 0 || at + 10 + frameSize > body.size) break
            if (id == "CHAP") chap(body, at + 10, frameSize, version)?.let(marks::add)
            at += 10 + frameSize
        }
        return marks
    }

    private fun chap(body: ByteArray, start: Int, size: Int, version: Int): Mark? {
        val end = start + size
        var at = start
        while (at < end && body[at] != 0.toByte()) at++
        at++ // the element id's terminator
        if (at + 16 > end) return null
        val startMs = int32(body, at).toLong() and 0xffffffffL
        at += 16
        var title: String? = null
        while (at + 10 <= end) {
            val id = String(body, at, 4, Charsets.ISO_8859_1)
            val sub = if (version == 4) syncsafe(body, at + 4) else int32(body, at + 4)
            if (sub <= 0 || at + 10 + sub > end) break
            if (id == "TIT2") title = id3Text(body, at + 10, sub)
            at += 10 + sub
        }
        return Mark(title?.trim().orEmpty(), startMs)
    }

    private fun id3Text(body: ByteArray, start: Int, size: Int): String {
        if (size < 1) return ""
        val charset: Charset = when (body[start].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(body, start + 1, size - 1, charset).trimEnd('\u0000')
    }

    private fun syncsafe(b: ByteArray, at: Int) =
        (b[at].toInt() and 0x7f shl 21) or (b[at + 1].toInt() and 0x7f shl 14) or
            (b[at + 2].toInt() and 0x7f shl 7) or (b[at + 3].toInt() and 0x7f)

    private fun int32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff shl 24) or (b[at + 1].toInt() and 0xff shl 16) or
            (b[at + 2].toInt() and 0xff shl 8) or (b[at + 3].toInt() and 0xff)

    // --- Vorbis comments: FLAC, Ogg Vorbis, Opus ---

    private fun flac(raf: RandomAccessFile): List<Mark> {
        raf.seek(4)
        while (raf.filePointer + 4 <= raf.length()) {
            val header = raf.readInt()
            val last = header ushr 31 == 1
            val type = (header ushr 24) and 0x7f
            val length = header and 0xffffff
            if (type == 4) return comments(ByteArray(length).also(raf::readFully), 0)
            raf.seek(raf.filePointer + length)
            if (last) break
        }
        return emptyList()
    }

    /** The comment packet is an Ogg stream's second: gathered from its pages' segments. */
    private fun ogg(raf: RandomAccessFile): List<Mark> {
        raf.seek(0)
        val packet = java.io.ByteArrayOutputStream()
        var packets = 0
        while (raf.filePointer + 27 <= raf.length() && packets < 2) {
            val head = ByteArray(27).also(raf::readFully)
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "OggS") break
            val lacing = ByteArray(head[26].toInt() and 0xff).also(raf::readFully)
            for (l in lacing) {
                val size = l.toInt() and 0xff
                val bytes = ByteArray(size).also(raf::readFully)
                if (packets == 1) packet.write(bytes)
                // A segment under 255 bytes ends its packet.
                if (size < 255 && ++packets == 2) break
            }
            if (packet.size() > 1 shl 20) break
        }
        val body = packet.toByteArray()
        val start = when {
            body.size > 7 && String(body, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> 7
            body.size > 8 && String(body, 0, 8, Charsets.ISO_8859_1) == "OpusTags" -> 8
            else -> return emptyList()
        }
        return comments(body, start)
    }

    private val chapterKey = Regex("^CHAPTER(\\d+)(NAME)?$")

    /** `CHAPTER001=00:01:02.500` and `CHAPTER001NAME=Title`, as Matroska and ffmpeg write them. */
    private fun comments(b: ByteArray, from: Int): List<Mark> {
        fun le(at: Int) = (b[at].toInt() and 0xff) or (b[at + 1].toInt() and 0xff shl 8) or
            (b[at + 2].toInt() and 0xff shl 16) or (b[at + 3].toInt() and 0xff shl 24)
        var at = from
        at += 4 + le(at) // vendor
        val count = le(at)
        at += 4
        val starts = HashMap<String, Long>()
        val names = HashMap<String, String>()
        for (i in 0 until count) {
            if (at + 4 > b.size) break
            val length = le(at)
            at += 4
            if (length < 0 || at + length > b.size) break
            val text = String(b, at, length, Charsets.UTF_8)
            at += length
            val match = chapterKey.find(text.substringBefore('=').uppercase()) ?: continue
            val number = match.groupValues[1]
            val value = text.substringAfter('=', "")
            if (match.groupValues[2].isEmpty()) timestamp(value)?.let { starts[number] = it } else names[number] = value
        }
        return starts.map { (n, ms) -> Mark(names[n].orEmpty(), ms) }
    }

    /** "01:02:03.500", or without hours or fraction, in milliseconds. */
    fun timestamp(s: String): Long? {
        val parts = s.trim().split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
        return hours * 3600_000 + minutes * 60_000 + (seconds * 1000).toLong()
    }

    // --- MP4 ---

    private class Box(val type: String, val start: Long, val header: Long, val size: Long) {
        val body get() = start + header
        val end get() = start + size
    }

    private fun boxes(raf: RandomAccessFile, from: Long, to: Long): List<Box> {
        val found = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            raf.seek(at)
            var size = raf.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also(raf::readFully).toString(Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) { size = raf.readLong(); header = 16 }
            if (size == 0L) size = to - at
            if (size < header) break
            found += Box(type, at, header, size)
            at += size
        }
        return found
    }

    private fun child(raf: RandomAccessFile, box: Box, type: String) = boxes(raf, box.body, box.end).firstOrNull { it.type == type }

    private fun mp4(raf: RandomAccessFile): List<Mark> {
        val moov = boxes(raf, 0, raf.length()).firstOrNull { it.type == "moov" } ?: return emptyList()
        val inMoov = boxes(raf, moov.body, moov.end)
        val nero = inMoov.firstOrNull { it.type == "udta" }?.let { child(raf, it, "chpl") }?.let { chpl(raf, it) }.orEmpty()
        if (nero.size > 1) return nero
        val quicktime = quicktimeChapters(raf, inMoov.filter { it.type == "trak" })
        return if (quicktime.size >= nero.size) quicktime else nero
    }

    private fun chpl(raf: RandomAccessFile, box: Box): List<Mark> {
        raf.seek(box.body)
        val version = raf.readUnsignedByte()
        raf.skipBytes(3)
        if (version == 1) raf.skipBytes(1)
        val count = if (version == 1) raf.readInt() else raf.readUnsignedByte()
        val marks = ArrayList<Mark>()
        repeat(count) {
            if (raf.filePointer + 9 > box.end) return marks
            val start = raf.readLong() / 10_000 // 100 ns units
            val length = raf.readUnsignedByte()
            val title = ByteArray(length).also(raf::readFully).toString(Charsets.UTF_8)
            marks += Mark(title, start)
        }
        return marks
    }

    private fun quicktimeChapters(raf: RandomAccessFile, traks: List<Box>): List<Mark> {
        // The audio track names its chapter track in tref/chap.
        val chapterIds = traks.mapNotNull { trak ->
            val tref = child(raf, trak, "tref") ?: return@mapNotNull null
            val chap = child(raf, tref, "chap") ?: return@mapNotNull null
            raf.seek(chap.body)
            raf.readInt()
        }.toSet()
        if (chapterIds.isEmpty()) return emptyList()
        val trak = traks.firstOrNull { trackId(raf, it) in chapterIds } ?: return emptyList()
        val mdia = child(raf, trak, "mdia") ?: return emptyList()
        val mdhd = child(raf, mdia, "mdhd") ?: return emptyList()
        raf.seek(mdhd.body)
        val timescale = if (raf.readUnsignedByte() == 1) { raf.skipBytes(3 + 16); raf.readInt() } else { raf.skipBytes(3 + 8); raf.readInt() }
        if (timescale <= 0) return emptyList()
        val stbl = child(raf, mdia, "minf")?.let { child(raf, it, "stbl") } ?: return emptyList()
        val parts = boxes(raf, stbl.body, stbl.end).associateBy { it.type }

        val durations = ArrayList<Long>()
        parts["stts"]?.let { stts ->
            raf.seek(stts.body + 4)
            repeat(raf.readInt()) {
                val count = raf.readInt()
                val delta = raf.readInt().toLong() and 0xffffffffL
                repeat(count.coerceAtMost(10_000)) { durations += delta }
            }
        } ?: return emptyList()

        val sizes = ArrayList<Int>()
        parts["stsz"]?.let { stsz ->
            raf.seek(stsz.body + 4)
            val fixed = raf.readInt()
            val count = raf.readInt().coerceAtMost(10_000)
            repeat(count) { sizes += if (fixed != 0) fixed else raf.readInt() }
        } ?: return emptyList()

        val chunkOffsets = ArrayList<Long>()
        (parts["stco"] ?: parts["co64"])?.let { box ->
            raf.seek(box.body + 4)
            repeat(raf.readInt().coerceAtMost(10_000)) {
                chunkOffsets += if (box.type == "co64") raf.readLong() else raf.readInt().toLong() and 0xffffffffL
            }
        } ?: return emptyList()

        // Which samples sit in which chunk.
        val runs = ArrayList<Pair<Int, Int>>() // first chunk (1-based), samples per chunk
        parts["stsc"]?.let { stsc ->
            raf.seek(stsc.body + 4)
            repeat(raf.readInt().coerceAtMost(10_000)) {
                val first = raf.readInt()
                val perChunk = raf.readInt()
                raf.readInt()
                runs += first to perChunk
            }
        }
        val sampleOffsets = ArrayList<Long>()
        var sample = 0
        for (chunk in chunkOffsets.indices) {
            val perChunk = runs.lastOrNull { it.first <= chunk + 1 }?.second ?: 1
            var at = chunkOffsets[chunk]
            repeat(perChunk) {
                if (sample < sizes.size) {
                    sampleOffsets += at
                    at += sizes[sample]
                    sample++
                }
            }
        }

        val marks = ArrayList<Mark>()
        var time = 0L
        for (i in 0 until minOf(sampleOffsets.size, sizes.size, durations.size)) {
            raf.seek(sampleOffsets[i])
            val length = raf.readUnsignedShort().coerceAtMost(sizes[i] - 2).coerceAtLeast(0)
            val bytes = ByteArray(length).also(raf::readFully)
            val title = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                String(bytes, Charsets.UTF_16)
            } else String(bytes, Charsets.UTF_8)
            marks += Mark(title, time * 1000 / timescale)
            time += durations[i]
        }
        return marks
    }

    private fun trackId(raf: RandomAccessFile, trak: Box): Int {
        val tkhd = child(raf, trak, "tkhd") ?: return -1
        raf.seek(tkhd.body)
        val version = raf.readUnsignedByte()
        raf.skipBytes(3 + if (version == 1) 16 else 8)
        return raf.readInt()
    }
}
