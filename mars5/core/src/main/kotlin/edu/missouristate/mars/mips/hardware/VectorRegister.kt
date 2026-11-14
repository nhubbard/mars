package edu.missouristate.mars.mips.hardware

/** A 128-bit register. Lane zero starts at the least-significant bit of [low]. */
@JvmRecord
data class VectorRegister(val low: Long, val high: Long) {
    fun toHexString(): String {
        val highHex = high.toULong().toString(16).padStart(16, '0')
        val lowHex = low.toULong().toString(16).padStart(16, '0')
        return "0x$highHex$lowHex"
    }

    /** Read an unsigned lane, represented by its raw bits when the lane is 64 bits wide. */
    fun unsignedLane(index: Int, width: Int): Long {
        require(width == 8 || width == 16 || width == 32 || width == 64) { "Vector lane width must be 8, 16, 32, or 64 bits" }
        require(index in 0 until 128 / width) { "Lane $index is outside the $width-bit vector lanes" }
        val offset = index * width
        val half = if (offset < 64) low else high
        return (half ushr (offset % 64)) and (-1L ushr (64 - width))
    }
}
