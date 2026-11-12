package edu.missouristate.mars.util

/** Formatting of signed numbers, addresses, and floating-point bit patterns. */
object NumberDisplayBase {
    const val DECIMAL = 10
    const val HEXADECIMAL = 16
    const val ASCII = 0

    @JvmStatic
    fun getBase(displayInHex: Boolean): Int = if (displayInHex) HEXADECIMAL else DECIMAL

    @JvmStatic
    fun formatUnsignedInteger(value: Int, base: Int): String =
        if (base == HEXADECIMAL) Binary.intToHexString(value) else Binary.unsignedIntToIntString(value)

    @JvmStatic
    fun formatNumber(value: Int, base: Int): String = when (base) {
        HEXADECIMAL -> Binary.intToHexString(value)
        ASCII -> Binary.intToAscii(value)
        else -> value.toString()
    }

    @JvmStatic
    fun formatNumber(value: Float, base: Int): String =
        if (base == HEXADECIMAL) Binary.intToHexString(value.toBits()) else value.toString()

    @JvmStatic
    fun formatNumber(value: Double, base: Int): String =
        if (base == HEXADECIMAL) Binary.longToHexString(value.toBits()) else value.toString()

    /** Hexadecimal output preserves NaN payload bits. */
    @JvmStatic
    fun formatFloatNumber(value: Int, base: Int): String =
        if (base == HEXADECIMAL) Binary.intToHexString(value) else Float.fromBits(value).toString()

    /** Hexadecimal output preserves NaN payload bits. */
    @JvmStatic
    fun formatDoubleNumber(value: Long, base: Int): String =
        if (base == HEXADECIMAL) Binary.longToHexString(value) else Double.fromBits(value).toString()
}
