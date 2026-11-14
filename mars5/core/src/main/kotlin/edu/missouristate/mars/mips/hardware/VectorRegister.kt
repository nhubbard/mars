package edu.missouristate.mars.mips.hardware

/** A 128-bit register. Lane zero starts at the least-significant bit of [low]. */
@JvmRecord
data class VectorRegister(val low: Long, val high: Long) {
    fun toHexString(): String {
        val highHex = high.toULong().toString(16).padStart(16, '0')
        val lowHex = low.toULong().toString(16).padStart(16, '0')
        return "0x$highHex$lowHex"
    }
}
