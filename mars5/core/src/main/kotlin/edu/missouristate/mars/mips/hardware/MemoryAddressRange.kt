package edu.missouristate.mars.mips.hardware

import java.lang.Long.decode as decodeLong
import kotlin.jvm.JvmRecord

/** A word-aligned range with nonnegative address bounds. */
@JvmRecord
data class MemoryAddressRange(val first: Long, val last: Long) {
  init {
    require(first in 0..last && first and 3L == 0L && last and 3L == 0L) {
      "Memory range requires ordered, word-aligned endpoints"
    }
  }

  override fun toString(): String = "MemoryAddressRange[first=$first, last=$last]"

  companion object {
    /** Distinguishes a range operand from other arguments containing hyphens. */
    @JvmStatic
    fun hasAddressPrefix(text: String): Boolean {
      val separator = text.indexOf('-', startIndex = 1)
      if (separator < 0) return false
      return try {
        decodeLong(text.substring(0, separator))
        true
      } catch (_: NumberFormatException) {
        false
      }
    }

    @JvmStatic
    fun parse(text: String): MemoryAddressRange {
      val separator = text.indexOf('-', startIndex = 1)
      if (separator > 0) {
        try {
          return MemoryAddressRange(
              address(text.substring(0, separator)),
              address(text.substring(separator + 1)),
          )
        } catch (_: IllegalArgumentException) {
          // Report malformed addresses together with the original range.
        }
      }
      throw IllegalArgumentException("Invalid or unaligned memory range: $text")
    }

    private fun address(text: String): Long {
      val value = decodeLong(text)
      if (value < Int.MIN_VALUE) throw NumberFormatException(text)
      return if (value < 0) value.toUInt().toLong() else value
    }
  }
}
