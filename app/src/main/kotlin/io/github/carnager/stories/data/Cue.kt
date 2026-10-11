package io.github.carnager.stories.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A CUE sheet's tracks as chapter marks, per file it names. Old sheets are
 * often in Windows' code page rather than UTF-8; both are understood.
 */
object Cue {
    fun read(file: File): Map<String, List<Mark>> = runCatching { parse(decode(file.readBytes())) }.getOrDefault(emptyMap())

    fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
            String(body, Charset.forName("windows-1252"))
        }
    }

    fun parse(text: String): Map<String, List<Mark>> {
        val result = LinkedHashMap<String, MutableList<Mark>>()
        var file: String? = null
        var title = ""
        var number = 0
        var inTrack = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val word = line.substringBefore(' ').uppercase()
            val rest = line.substringAfter(' ', "").trim()
            when (word) {
                "FILE" -> {
                    file = unquote(rest.substringBeforeLast(' ').ifEmpty { rest })
                    inTrack = false
                }
                "TRACK" -> {
                    inTrack = true
                    number = rest.substringBefore(' ').toIntOrNull() ?: (number + 1)
                    title = ""
                }
                "TITLE" -> if (inTrack) title = unquote(rest)
                "INDEX" -> {
                    if (!inTrack || file == null) continue
                    val (index, time) = rest.split(Regex("\\s+")).let { it.getOrNull(0) to it.getOrNull(1) }
                    if (index?.toIntOrNull() != 1) continue
                    val ms = frames(time ?: continue) ?: continue
                    result.getOrPut(file) { ArrayList() } += Mark(title.ifEmpty { "Track $number" }, ms)
                }
            }
        }
        return result
    }

    /** mm:ss:ff, ff being 75ths of a second. */
    private fun frames(s: String): Long? {
        val p = s.split(':').map { it.toLongOrNull() ?: return null }
        if (p.size != 3) return null
        return p[0] * 60_000 + p[1] * 1000 + p[2] * 1000 / 75
    }

    private fun unquote(s: String) = s.trim().removeSurrounding("\"")

    /**
     * Which of [audio] a sheet's file name means: the same name, or the same
     * name with another extension -- sheets often still name the .wav they
     * were ripped to -- or, with one file each, that one.
     */
    fun match(sheet: Map<String, List<Mark>>, audio: List<String>): Map<String, List<Mark>> {
        val byName = audio.associateBy { it.substringAfterLast('/') }
        val byStem = audio.groupBy { it.substringAfterLast('/').substringBeforeLast('.') }
        val result = HashMap<String, List<Mark>>()
        for ((name, marks) in sheet) {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            val path = byName[base] ?: byStem[base.substringBeforeLast('.')]?.singleOrNull()
                ?: if (sheet.size == 1 && audio.size == 1) audio.first() else null
            if (path != null && marks.size > 1) result[path] = marks
        }
        return result
    }
}
