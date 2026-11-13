package edu.missouristate.mars.plugin.tools

/** Sequential output and the MARS cursor-addressed display, independent of Swing. */
internal class MmioTerminalDisplay private constructor(
    val geometry: Geometry, private val stream: Chunk?, private val cells: String, val cursor: Int?,
) {
    data class Geometry(val columns: Int = 80, val rows: Int = 24) {
        init {
            require(columns in columnRange)
            require(rows in rowRange)
        }

        companion object {
            val columnRange = 1..256
            val rowRange = 1..128
        }
    }

    constructor(geometry: Geometry = Geometry()) : this(geometry, null, "", null)

    val caretOffset: Int? get() = cursor?.let { it + it / geometry.columns }

    val text: String
        get() = if (cursor == null) {
            val chunks = ArrayDeque<String>()
            var chunk = stream
            while (chunk != null) {
                chunks.addFirst(chunk.text)
                chunk = chunk.previous
            }
            chunks.joinToString("")
        } else cells.chunked(geometry.columns).joinToString("\n")

    fun transmit(word: Int): MmioTerminalDisplay {
        val (columns, rows) = geometry
        return when (val character = (word and 0xff).toChar()) {
            '\u000c' -> if (cursor == null) MmioTerminalDisplay(geometry) else addressed(blank(), 0)
            '\u0007' -> {
                val column = (word ushr 20).coerceAtMost(columns - 1)
                val row = ((word ushr 8) and 0xfff).coerceAtMost(rows - 1)
                addressed(if (cursor == null) blank() else cells, row * columns + column)
            }
            else -> if (cursor == null) {
                MmioTerminalDisplay(geometry, append(character), "", null)
            } else {
                when (character) {
                    '\r' -> addressed(cells, cursor / columns * columns)
                    '\n' -> addressed(cells, (cursor / columns + 1).coerceAtMost(rows - 1) * columns)
                    else -> addressed(cells.replaceRange(cursor, cursor + 1, character.toString()),
                        (cursor + 1).coerceAtMost(cells.lastIndex))
                }
            }
        }
    }

    private fun blank() = " ".repeat(geometry.columns * geometry.rows)

    private fun addressed(text: String, position: Int) = MmioTerminalDisplay(geometry, null, text, position)

    // Checkpoints share completed chunks, so sequential output copies only the last short chunk.
    private class Chunk(val text: String, val previous: Chunk?)

    private fun append(character: Char): Chunk {
        val tail = stream
        return if (tail == null || tail.text.length == 256) Chunk(character.toString(), tail)
        else Chunk(tail.text + character, tail.previous)
    }
}
