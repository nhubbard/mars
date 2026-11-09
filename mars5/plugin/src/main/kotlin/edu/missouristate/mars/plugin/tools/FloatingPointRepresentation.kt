package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.util.Binary

/** IEEE 754 binary32, with the original sign and payload bits intact. */
internal data class FloatingPointRepresentation(val bits: Int) {
    enum class Kind(val messageKey: String) {
        ZERO("float.zero"),
        SUBNORMAL("float.subnormal"),
        NORMAL("float.normal"),
        INFINITY("float.infinity"),
        NAN("float.nan"),
    }

    val sign = bits ushr 31
    val exponent = bits ushr 23 and 0xff
    val fraction = bits and 0x7fffff
    val kind = when (exponent) {
        0 -> if (fraction == 0) Kind.ZERO else Kind.SUBNORMAL
        0xff -> if (fraction == 0) Kind.INFINITY else Kind.NAN
        else -> Kind.NORMAL
    }
    val hex get() = Binary.intToHexString(bits)
    val decimal get() = Float.fromBits(bits).toString()
    val exponentBits get() = exponent.toString(2).padStart(8, '0')
    val fractionBits get() = fraction.toString(2).padStart(23, '0')
    val finiteExponent get() = maxOf(1, exponent) - 127

    companion object {
        private val hexDigits = Regex("[0-9a-fA-F]{1,8}")

        fun fromDecimal(text: String): FloatingPointRepresentation? =
            text.trim().toFloatOrNull()?.let { FloatingPointRepresentation(it.toRawBits()) }

        fun fromHex(text: String): FloatingPointRepresentation? {
            val trimmed = text.trim()
            val digits = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed.drop(2) else trimmed
            if (!hexDigits.matches(digits)) return null
            return FloatingPointRepresentation(digits.toUInt(16).toInt())
        }

        fun fromBinary(sign: String, exponent: String, fraction: String): FloatingPointRepresentation? {
            val s = binaryField(sign, 1) ?: return null
            val e = binaryField(exponent, 8) ?: return null
            val f = binaryField(fraction, 23) ?: return null
            return FloatingPointRepresentation((s shl 31) or (e shl 23) or f)
        }

        private fun binaryField(text: String, width: Int): Int? {
            val digits = text.trim()
            if (digits.length > width || digits.any { it != '0' && it != '1' }) return null
            return digits.padStart(width, '0').toInt(2)
        }
    }
}
