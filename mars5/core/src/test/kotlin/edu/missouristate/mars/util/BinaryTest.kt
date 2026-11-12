package edu.missouristate.mars.util

import edu.missouristate.mars.util.NumberDisplayBase.HEXADECIMAL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BinaryTest {
    @Test
    fun `full width words accept unsigned hexadecimal and preserve decimal wrapping`() {
        assertEquals(Int.MIN_VALUE, Binary.stringToInt("0X80000000"))
        assertEquals(Long.MIN_VALUE, Binary.stringToLong("0x8000000000000000"))
        assertEquals(1, Binary.stringToInt("4294967297"))
        assertThrows(NumberFormatException::class.java) { Binary.stringToLong("18446744073709551615") }
        assertThrows(NumberFormatException::class.java) { Binary.stringToInt("0x8000000g") }
    }

    @Test
    fun `number spelling classification preserves assembler behavior`() {
        assertTrue(Binary.isHex("0xffffffffffffffff"))
        assertFalse(Binary.isHex("#ff"))
        assertFalse(Binary.isHex("-0x1"))
        assertEquals(-1, Binary.stringToInt("-0x1"))
        assertTrue(Binary.isOctal("077"))
        assertTrue(Binary.isOctal("-0"))
        assertTrue(Binary.isOctal("09"))
        assertFalse(Binary.isOctal("0"))
        assertEquals(9, Binary.stringToInt("09"))
    }

    @Test
    fun `register bit patterns preserve NaN payloads in hexadecimal`() {
        val floatBits = 0x7fc00001
        val doubleBits = 0x7ff8000000000001L
        assertEquals("0x7fc00001", NumberDisplayBase.formatFloatNumber(floatBits, HEXADECIMAL))
        assertEquals("0x7ff8000000000001", NumberDisplayBase.formatDoubleNumber(doubleBits, HEXADECIMAL))
        assertEquals("0x7fc00000", NumberDisplayBase.formatNumber(Float.fromBits(floatBits), HEXADECIMAL))
        assertEquals("0x7ff8000000000000", NumberDisplayBase.formatNumber(Double.fromBits(doubleBits), HEXADECIMAL))
    }
}
