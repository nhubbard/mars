package edu.missouristate.mars.plugin.tools

/** Sequential output and the MARS cursor-addressed display, independent of Swing. */
internal class MmioTerminalDisplay private constructor(
    private val stream: Chunk?, private val cells: String, val cursor: Int?,
) {
    constructor() : this(null, "", null)

    val text: String
        get() = if (cursor == null) {
            val chunks = ArrayDeque<String>()
            var chunk = stream
            while (chunk != null) {
                chunks.addFirst(chunk.text)
                chunk = chunk.previous
            }
            chunks.joinToString("")
        } else cells.chunked(columns).joinToString("\n")

    fun transmit(word: Int): MmioTerminalDisplay {
        val character = (word and 0xff).toChar()
        return when (character) {
            '\u000c' -> if (cursor == null) MmioTerminalDisplay() else MmioTerminalDisplay(null, blank, 0)
            '\u0007' -> {
                val column = (word ushr 20).coerceAtMost(columns - 1)
                val row = ((word ushr 8) and 0xfff).coerceAtMost(rows - 1)
                MmioTerminalDisplay(null, if (cursor == null) blank else cells, row * columns + column)
            }
            else -> if (cursor == null) {
                MmioTerminalDisplay(append(character), "", null)
            } else {
                when (character) {
                    '\r' -> MmioTerminalDisplay(null, cells, cursor / columns * columns)
                    '\n' -> MmioTerminalDisplay(null, cells, (cursor / columns + 1).coerceAtMost(rows - 1) * columns)
                    else -> MmioTerminalDisplay(null, cells.replaceRange(cursor, cursor + 1, character.toString()),
                        (cursor + 1).coerceAtMost(blank.lastIndex))
                }
            }
        }
    }

    // Checkpoints share completed chunks, so sequential output copies only the last short chunk.
    private class Chunk(val text: String, val previous: Chunk?)

    private fun append(character: Char): Chunk {
        val tail = stream
        return if (tail == null || tail.text.length == 256) Chunk(character.toString(), tail)
        else Chunk(tail.text + character, tail.previous)
    }

    companion object {
        const val columns = 80
        const val rows = 24
        private val blank = " ".repeat(columns * rows)
    }
}
