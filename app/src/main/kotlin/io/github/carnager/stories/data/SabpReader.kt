package io.github.carnager.stories.data

import java.io.DataInputStream
import java.io.File

/**
 * Smart AudioBook Player's `position.sabp.dat`, which it can leave in each
 * book's folder: a Java-serialized `BookDataBackup`. Its classes aren't
 * here, so this reads the stream itself -- just enough of the format for
 * plain objects, strings, enums, arrays and `java.util.Date`.
 */
data class SabpPosition(
    val fileName: String,
    /** Seconds into [fileName]. */
    val fileSeconds: Int,
    /** Seconds into the whole book, as SABP last counted it. */
    val bookSeconds: Int,
    val totalSeconds: Int,
    val state: String,
    val speed: Float,
    val lastPlayed: Long,
)

object SabpReader {
    const val FILE_NAME = "position.sabp.dat"

    fun read(file: File): SabpPosition? = runCatching {
        file.inputStream().buffered().use { parse(DataInputStream(it)) }
    }.getOrNull()

    fun parse(input: DataInputStream): SabpPosition? {
        if (input.readUnsignedShort() != 0xACED) return null
        input.readUnsignedShort() // version
        val root = Stream(input).content() as? Obj ?: return null
        val f = root.fields
        return SabpPosition(
            fileName = f["mFileName"] as? String ?: return null,
            fileSeconds = (f["mFilePosition"] as? Int) ?: 0,
            bookSeconds = (f["mCurrentPlaybackTimeCache"] as? Int) ?: 0,
            totalSeconds = (f["mTotalPlaybackTimeCache"] as? Int) ?: 0,
            state = (f["mBookState"] as? EnumValue)?.name ?: "",
            speed = (f["mPlaybackSpeed"] as? Float)?.takeIf { it in 0.25f..5f } ?: 1f,
            lastPlayed = (f["mLastPlaybackTime"] as? Long) ?: 0L,
        )
    }

    private class Desc(val name: String, val flags: Int, val fields: List<Pair<Char, String>>, val parent: Desc?)
    private class Obj(val desc: Desc, val fields: MutableMap<String, Any?> = HashMap())
    private class EnumValue(val name: String)

    private class Stream(val input: DataInputStream) {
        private val handles = ArrayList<Any?>()

        // InputStream.skipNBytes is Android 14 and later.
        private fun skip(count: Int) = input.readFully(ByteArray(count))

        private fun handle(value: Any?): Int { handles += value; return handles.lastIndex }

        fun content(tc: Int = input.readUnsignedByte()): Any? = when (tc) {
            0x70 -> null // TC_NULL
            0x71 -> handles[input.readInt() - 0x7E0000] // TC_REFERENCE
            0x73 -> obj() // TC_OBJECT
            0x74 -> input.readUTF().also { handle(it) } // TC_STRING
            0x7C -> longString() // TC_LONGSTRING
            0x7E -> enumValue() // TC_ENUM
            0x75 -> array() // TC_ARRAY
            0x72, 0x7D -> classDesc(tc) // TC_CLASSDESC
            0x77 -> { skip(input.readUnsignedByte()); content() } // TC_BLOCKDATA
            0x7A -> { skip(input.readInt()); content() } // TC_BLOCKDATALONG
            else -> error("unsupported tag ${tc.toString(16)}")
        }

        private fun longString(): String {
            val bytes = ByteArray(input.readLong().toInt()).also(input::readFully)
            return String(bytes, Charsets.UTF_8).also { handle(it) }
        }

        private fun descriptor(): Desc? = when (val tc = input.readUnsignedByte()) {
            0x70 -> null
            0x71 -> handles[input.readInt() - 0x7E0000] as Desc
            0x72, 0x7D -> classDesc(tc)
            else -> error("expected a class, got ${tc.toString(16)}")
        }

        private fun classDesc(tc: Int): Desc {
            if (tc == 0x7D) error("proxy classes unsupported")
            val name = input.readUTF()
            input.readLong() // serialVersionUID
            val index = handle(null)
            val flags = input.readUnsignedByte()
            val fields = (0 until input.readUnsignedShort()).map {
                val type = input.readUnsignedByte().toChar()
                val field = input.readUTF()
                if (type == 'L' || type == '[') content() // the field's class name
                type to field
            }
            skipAnnotations()
            val desc = Desc(name, flags, fields, descriptor())
            handles[index] = desc
            return desc
        }

        private fun skipAnnotations() {
            while (true) {
                val tc = input.readUnsignedByte()
                when (tc) {
                    0x78 -> return // TC_ENDBLOCKDATA
                    0x77 -> skip(input.readUnsignedByte())
                    0x7A -> skip(input.readInt())
                    else -> content(tc)
                }
            }
        }

        private fun obj(): Obj {
            val desc = descriptor() ?: error("object without class")
            val o = Obj(desc)
            handle(o)
            // Superclasses' fields come first.
            val chain = generateSequence(desc) { it.parent }.toList().asReversed()
            for (d in chain) {
                for ((type, name) in d.fields) o.fields[name] = value(type)
                // A class that writes itself (SC_WRITE_METHOD) adds data after its fields.
                if (d.flags and 0x01 != 0) {
                    if (d.name == "java.util.Date") {
                        // Its one long, in a block: the time.
                        val tc = input.readUnsignedByte()
                        if (tc == 0x77) {
                            val length = input.readUnsignedByte()
                            if (length >= 8) { o.fields["time"] = input.readLong(); skip((length - 8)) }
                            else skip(length)
                            skipAnnotations()
                        } else if (tc != 0x78) {
                            content(tc)
                            skipAnnotations()
                        }
                    } else {
                        skipAnnotations()
                    }
                }
            }
            return o
        }

        private fun enumValue(): EnumValue {
            descriptor()
            val index = handle(null)
            val name = content() as String
            return EnumValue(name).also { handles[index] = it }
        }

        private fun array(): Any? {
            val desc = descriptor() ?: error("array without class")
            val index = handle(null)
            val length = input.readInt()
            val element = desc.name.getOrElse(1) { 'L' }
            val values = (0 until length).map { value(element) }
            handles[index] = values
            return values
        }

        private fun value(type: Char): Any? = when (type) {
            'B' -> input.readByte()
            'C' -> input.readChar()
            'D' -> input.readDouble()
            'F' -> input.readFloat()
            'I' -> input.readInt()
            'J' -> input.readLong()
            'S' -> input.readShort()
            'Z' -> input.readBoolean()
            else -> content()
        }
    }
}
