package edu.missouristate.mars.mips.dump

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState.Companion.WORD_LENGTH_BYTES

/** A detached memory range and the display options at the moment it was captured. */
class MemoryDumpSnapshot
private constructor(
    private val firstAddress: Long,
    private val words: IntArray,
    internal val segment: Segment,
    private val sourceLines: List<SourceLine>,
    internal val hexAddresses: Boolean,
    internal val hexValues: Boolean,
) {
  internal enum class Segment {
    TEXT,
    DATA,
    OTHER,
  }

  internal data class SourceLine(val instruction: String, val line: Int, val source: String)

  fun size(): Int = words.size

  internal fun address(index: Int): Long = firstAddress + index.toLong() * WORD_LENGTH_BYTES

  internal fun word(index: Int): Int = words[index]

  internal fun sourceLine(index: Int): SourceLine = sourceLines[index]

  companion object {
    @Throws(AddressErrorException::class)
    internal fun capture(
        session: MarsContext,
        firstAddress: Long,
        lastAddress: Long,
        includeSource: Boolean,
        checkCancellation: () -> Unit,
    ): MemoryDumpSnapshot {
      val memory = session.memory
      val segment =
          when {
            memory.inUserText(firstAddress) -> Segment.TEXT
            memory.inUserData(firstAddress) -> Segment.DATA
            else -> Segment.OTHER
          }
      val captureSource = includeSource && segment == Segment.TEXT
      val sourceLines = ArrayList<SourceLine>()
      var words = IntArray(64)
      var count = 0
      var address = firstAddress
      while (address <= lastAddress) {
        checkCancellation()
        val word =
            if (captureSource) {
              val statement = memory.getStatementNoNotify(address) ?: break
              sourceLines.add(
                  SourceLine(
                      statement.printableBasicAssemblyStatement,
                      statement.sourceLine,
                      statement.source!!,
                  ),
              )
              statement.binaryStatement
            } else {
              memory.getDumpWordOrNull(address) ?: break
            }
        if (count == words.size) words = words.copyOf(count * 2)
        words[count++] = word
        address += WORD_LENGTH_BYTES
      }
      return MemoryDumpSnapshot(
          firstAddress,
          words.copyOf(count),
          segment,
          sourceLines,
          session.settings.displayAddressesInHex(),
          session.settings.displayValuesInHex(),
      )
    }
  }
}
