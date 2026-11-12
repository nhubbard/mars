package edu.missouristate.mars.mips.hardware

import java.lang.Long.decode as decodeLong
import kotlin.jvm.JvmRecord

/** A word-aligned range in the unsigned 32-bit address space. */
@JvmRecord
data class MemoryAddressRange(val first: Int, val last: Int) {
    init {
        require(first.toUInt() <= last.toUInt() && MemoryState.wordAligned(first) && MemoryState.wordAligned(last)) {
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
                    return MemoryAddressRange(address(text.substring(0, separator)), address(text.substring(separator + 1)))
                } catch (_: IllegalArgumentException) {
                    // Report malformed addresses together with the original range.
                }
            }
            throw IllegalArgumentException("Invalid or unaligned memory range: $text")
        }

        private fun address(text: String): Int {
            val value = decodeLong(text)
            if (value < Int.MIN_VALUE || value > 0xffffffffL) throw NumberFormatException(text)
            return value.toInt()
        }
    }
}
