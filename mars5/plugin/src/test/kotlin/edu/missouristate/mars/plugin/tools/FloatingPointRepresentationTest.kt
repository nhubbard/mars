package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.plugin.tools.FloatingPointRepresentation.Format.BINARY32
import edu.missouristate.mars.plugin.tools.FloatingPointRepresentation.Format.BINARY64
import edu.missouristate.mars.plugin.tools.FloatingPointRepresentation.Kind
import java.lang.Float.MIN_NORMAL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FloatingPointRepresentationTest {
  @Test
  fun `classifies binary32 without losing negative zero or a NaN payload`() {
    val cases =
        listOf(
            Triple(0x00000000, Kind.ZERO, "0.0"),
            Triple(0x80000000.toInt(), Kind.ZERO, "-0.0"),
            Triple(0x00000001, Kind.SUBNORMAL, Float.MIN_VALUE.toString()),
            Triple(0x00800000, Kind.NORMAL, MIN_NORMAL.toString()),
            Triple(0xbfc00000.toInt(), Kind.NORMAL, "-1.5"),
            Triple(0x7f800000, Kind.INFINITY, "Infinity"),
            Triple(0xff800000.toInt(), Kind.INFINITY, "-Infinity"),
            Triple(0xff800123.toInt(), Kind.NAN, "NaN"),
        )
    for ((bits, kind, decimal) in cases) {
      val value = FloatingPointRepresentation(bits)
      assertEquals(kind, value.kind)
      assertEquals(decimal, value.decimal)
      assertEquals(value, FloatingPointRepresentation.fromHex(value.hex))
      assertEquals(
          value,
          FloatingPointRepresentation.fromBinary(
              value.sign.toString(),
              value.exponentBits,
              value.fractionBits,
          ),
      )
    }
    val smallest = FloatingPointRepresentation(1)
    assertEquals(-126, smallest.finiteExponent)
    assertEquals("00000000", smallest.exponentBits)
    assertEquals("00000000000000000000001", smallest.fractionBits)
    val normal = FloatingPointRepresentation(0xbfc00000.toInt())
    assertEquals(0, normal.finiteExponent)
    assertEquals(127, normal.exponent)
    assertEquals(0x400000L, normal.fraction)
  }

  @Test
  fun `binary64 preserves sign subnormal exponents and the complete NaN payload`() {
    val payload = 0xfff8_abcd_1234_5678UL.toLong()
    for ((bits, kind) in
        listOf(
            0L to Kind.ZERO,
            Long.MIN_VALUE to Kind.ZERO,
            1L to Kind.SUBNORMAL,
            0x3ff0_0000_0000_0000L to Kind.NORMAL,
            0x7ff0_0000_0000_0000L to Kind.INFINITY,
            payload to Kind.NAN,
        )) {
      val value = FloatingPointRepresentation(bits, BINARY64)
      assertEquals(kind, value.kind)
      assertEquals(11, value.exponentBits.length)
      assertEquals(52, value.fractionBits.length)
      assertEquals(value, FloatingPointRepresentation.fromHex(value.hex, BINARY64))
      assertEquals(
          value,
          FloatingPointRepresentation.fromBinary(
              value.sign.toString(),
              value.exponentBits,
              value.fractionBits,
              BINARY64,
          ),
      )
    }
    assertEquals(-1022, FloatingPointRepresentation(1L, BINARY64).finiteExponent)
    assertEquals(Long.MIN_VALUE, FloatingPointRepresentation.fromDecimal("-0.0", BINARY64)?.bits)
    val negativeZero = FloatingPointRepresentation(Long.MIN_VALUE, BINARY64)
    assertEquals(negativeZero, negativeZero.convertTo(BINARY64))
    assertEquals(negativeZero, negativeZero.convertTo(BINARY32).convertTo(BINARY64))
    assertNull(FloatingPointRepresentation.fromDecimal("1e", BINARY64))
    assertNull(FloatingPointRepresentation.fromHex("10000000000000000", BINARY64))
    assertNull(FloatingPointRepresentation.fromBinary("1", "1", "1".repeat(53), BINARY64))
  }

  @Test
  fun `parses decimal hex and individual bit fields and rejects malformed input`() {
    assertEquals(0x80000000L, FloatingPointRepresentation.fromDecimal(" -0.0 ")?.bits)
    assertEquals(
        Float.NaN.toRawBits().toUInt().toLong(),
        FloatingPointRepresentation.fromDecimal("NaN")?.bits,
    )
    assertEquals(0x7f800000L, FloatingPointRepresentation.fromDecimal("1e39")?.bits)
    assertNull(FloatingPointRepresentation.fromDecimal("1e"))
    assertEquals(15L, FloatingPointRepresentation.fromHex(" 0XF ")?.bits)
    assertEquals(15L, FloatingPointRepresentation.fromHex("f")?.bits)
    for (input in listOf("", "0x", "0x0X1", "100000000", "-1", "+1", "g")) {
      assertNull(FloatingPointRepresentation.fromHex(input), input)
    }
    assertEquals(0L, FloatingPointRepresentation.fromBinary("", "", "")?.bits)
    assertEquals(0x80800001L, FloatingPointRepresentation.fromBinary(" 1 ", "1", "1")?.bits)
    assertNull(FloatingPointRepresentation.fromBinary("10", "", ""))
    assertNull(FloatingPointRepresentation.fromBinary("0", "100000000", ""))
    assertNull(FloatingPointRepresentation.fromBinary("0", "1", "0".repeat(24)))
    assertNull(FloatingPointRepresentation.fromBinary("0", "1", "02"))
  }
}
