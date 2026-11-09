package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.plugin.tools.FloatingPointRepresentation.Kind
import java.lang.Float.MIN_NORMAL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FloatingPointRepresentationTest {
    @Test
    fun `classifies binary32 without losing negative zero or a NaN payload`() {
        val cases = listOf(
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
            assertEquals(value, FloatingPointRepresentation.fromBinary(value.sign.toString(), value.exponentBits, value.fractionBits))
        }
        val smallest = FloatingPointRepresentation(1)
        assertEquals(-126, smallest.finiteExponent)
        assertEquals("00000000", smallest.exponentBits)
        assertEquals("00000000000000000000001", smallest.fractionBits)
        val normal = FloatingPointRepresentation(0xbfc00000.toInt())
        assertEquals(0, normal.finiteExponent)
        assertEquals(127, normal.exponent)
        assertEquals(0x400000, normal.fraction)
    }

    @Test
    fun `parses decimal hex and individual bit fields and rejects malformed input`() {
        assertEquals(0x80000000.toInt(), FloatingPointRepresentation.fromDecimal(" -0.0 ")?.bits)
        assertEquals(Float.NaN.toRawBits(), FloatingPointRepresentation.fromDecimal("NaN")?.bits)
        assertEquals(0x7f800000, FloatingPointRepresentation.fromDecimal("1e39")?.bits)
        assertNull(FloatingPointRepresentation.fromDecimal("1e"))
        assertEquals(15, FloatingPointRepresentation.fromHex(" 0XF ")?.bits)
        assertEquals(15, FloatingPointRepresentation.fromHex("f")?.bits)
        for (input in listOf("", "0x", "0x0X1", "100000000", "-1", "+1", "g")) {
            assertNull(FloatingPointRepresentation.fromHex(input), input)
        }
        assertEquals(0, FloatingPointRepresentation.fromBinary("", "", "")?.bits)
        assertEquals(0x80800001.toInt(), FloatingPointRepresentation.fromBinary(" 1 ", "1", "1")?.bits)
        assertNull(FloatingPointRepresentation.fromBinary("10", "", ""))
        assertNull(FloatingPointRepresentation.fromBinary("0", "100000000", ""))
        assertNull(FloatingPointRepresentation.fromBinary("0", "1", "0".repeat(24)))
        assertNull(FloatingPointRepresentation.fromBinary("0", "1", "02"))
    }
}
