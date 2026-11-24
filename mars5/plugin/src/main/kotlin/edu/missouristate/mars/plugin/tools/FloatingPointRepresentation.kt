package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.plugin.tools.FloatingPointRepresentation.Format.BINARY32
import edu.missouristate.mars.plugin.tools.FloatingPointRepresentation.Format.BINARY64
import edu.missouristate.mars.util.Binary

/** IEEE 754 scalar representation, with the original sign and payload bits intact. */
internal data class FloatingPointRepresentation(val bits: Long, val format: Format) {
  constructor(bits: Int) : this(bits.toUInt().toLong(), BINARY32)

  enum class Format(val exponentWidth: Int, val fractionWidth: Int, val messageKey: String) {
    BINARY32(8, 23, "float.binary32"),
    BINARY64(11, 52, "float.binary64");

    val signShift = exponentWidth + fractionWidth
    val exponentMask = (1 shl exponentWidth) - 1
    val fractionMask = (1L shl fractionWidth) - 1
  }

  enum class Kind(val messageKey: String) {
    ZERO("float.zero"),
    SUBNORMAL("float.subnormal"),
    NORMAL("float.normal"),
    INFINITY("float.infinity"),
    NAN("float.nan"),
  }

  val sign = (bits ushr format.signShift).toInt()
  val exponent = (bits ushr format.fractionWidth).toInt() and format.exponentMask
  val fraction = bits and format.fractionMask
  val kind =
      when (exponent) {
        0 -> if (fraction == 0L) Kind.ZERO else Kind.SUBNORMAL
        format.exponentMask -> if (fraction == 0L) Kind.INFINITY else Kind.NAN
        else -> Kind.NORMAL
      }
  val hex
    get() =
        if (format == BINARY32) Binary.intToHexString(bits.toInt())
        else Binary.longToHexString(bits)

  val decimal
    get() =
        if (format == BINARY32) Float.fromBits(bits.toInt()).toString()
        else Double.fromBits(bits).toString()

  val exponentBits
    get() = exponent.toString(2).padStart(format.exponentWidth, '0')

  val fractionBits
    get() = fraction.toString(2).padStart(format.fractionWidth, '0')

  val finiteExponent
    get() = maxOf(1, exponent) - (format.exponentMask ushr 1)

  fun convertTo(target: Format): FloatingPointRepresentation =
      when {
        format == target -> this
        target == BINARY32 ->
            FloatingPointRepresentation(Double.fromBits(bits).toFloat().toRawBits())
        else ->
            FloatingPointRepresentation(
                Float.fromBits(bits.toInt()).toDouble().toRawBits(),
                BINARY64,
            )
      }

  companion object {
    private val hexDigits = Regex("[0-9a-fA-F]+")

    fun fromDecimal(text: String, format: Format = BINARY32): FloatingPointRepresentation? =
        when (format) {
          BINARY32 ->
              text.trim().toFloatOrNull()?.let { FloatingPointRepresentation(it.toRawBits()) }
          BINARY64 ->
              text.trim().toDoubleOrNull()?.let {
                FloatingPointRepresentation(it.toRawBits(), format)
              }
        }

    fun fromHex(text: String, format: Format = BINARY32): FloatingPointRepresentation? {
      val trimmed = text.trim()
      val digits = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed.drop(2) else trimmed
      if (digits.length > (format.signShift + 1) / 4 || !hexDigits.matches(digits)) return null
      return FloatingPointRepresentation(digits.toULong(16).toLong(), format)
    }

    fun fromBinary(
        sign: String,
        exponent: String,
        fraction: String,
        format: Format = BINARY32,
    ): FloatingPointRepresentation? {
      val s = binaryField(sign, 1) ?: return null
      val e = binaryField(exponent, format.exponentWidth) ?: return null
      val f = binaryField(fraction, format.fractionWidth) ?: return null
      return FloatingPointRepresentation(
          (s shl format.signShift) or (e shl format.fractionWidth) or f,
          format,
      )
    }

    private fun binaryField(text: String, width: Int): Long? {
      val digits = text.trim()
      if (digits.length > width || digits.any { it != '0' && it != '1' }) return null
      return digits.ifEmpty { "0" }.toLong(2)
    }
  }
}
