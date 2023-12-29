/*
 * Copyright (c) 2003-2023, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2023-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

@file:Suppress("NAME_SHADOWING")

package edu.missouristate.mars.util

import edu.missouristate.mars.Globals
import edu.missouristate.mars.decodeToLong
import java.util.*

object Binary {
    private val chars = "0123456789abcdef".toCharArray()
    private const val UNSIGNED_BASE = 0x7FFFFFFF.toLong() + 0x7FFFFFFF.toLong() + 2L // 0xFFFFFFFF+1

    /**
     * Translate an integer value into a String consisting of ones and zeros.
     *
     * @param value The integer value to convert.
     * @param length The number of bit positions, starting at least significant, to process. Defaults to 32.
     * @return String consisting of ones and zeroes corresponding to the requested binary sequence.
     */
    @JvmStatic
    @JvmOverloads
    fun intToBinaryString(value: Int, length: Int = 32): String {
        val result = CharArray(length)
        var index = length - 1
        for (i in 0..<length) {
            result[index] = if (bitValue(value, i) == 1) '1' else '0'
            index--
        }
        return result.concatToString()
    }

    /**
     * Translate a long value into a String consisting of ones and zeros.
     *
     * @param value The long value to convert.
     * @param length The number of bit positions, starting at least significant, to process. Defaults to 64.
     * @return String consisting of ones and zeroes corresponding to the requested binary sequence.
     */
    @JvmStatic
    @JvmOverloads
    fun longToBinaryString(value: Long, length: Int = 64): String {
        val result = CharArray(length)
        var index = length - 1
        for (i in 0..<length) {
            result[index] = if (bitValue(value, i) == 1L) '1' else '0'
            index--
        }
        return result.concatToString()
    }

    /**
     * Translate a String consisting of ones and zeroes into an integer value having that binary representation.
     * The String is assumed to be at most 32 characters long.
     * No error checking is performed.
     * String position zero has the most-significant bit, position length - 1 has the least-significant bit.
     *
     * @param value The String value to convert.
     * @return An integer whose binary value corresponds to the decoded String.
     */
    @JvmStatic
    fun binaryStringToInt(value: String): Int {
        var result: Int = value[0].digitToInt() - 48
        for (i in 1..<value.length) result = (result shl 1) or (value[i].digitToInt() - 48)
        return result
    }

    /**
     * Translate a String consisting of ones and zeroes into a long value having that binary representation.
     * The String is assumed to be at most 64 characters long.
     * No error checking is performed.
     * String position zero has the most-significant bit, position length - 1 has the least-significant bit.
     *
     * @param value The String value to convert.
     * @return A long whose binary value corresponds to the decoded String.
     */
    @JvmStatic
    fun binaryStringToLong(value: String): Long {
        var result: Long = value[0].digitToInt().toLong() - 48
        for (i in 1..<value.length) result = (result shl 1) or (value[i].digitToInt().toLong() - 48)
        return result
    }

    /**
     * Translate a String consisting entirely of ones and zeroes into the String equivalent of the corresponding
     * hexadecimal value.
     * No length limit.
     * String position 0 has the most-significant bit, position length - 1 has the least-significant bit.
     *
     * @param value The String value to convert.
     * @return The String containing zero, one, ..., F characters that contains the hexadecimal equivalent of the
     * decoded String.
     */
    @JvmStatic
    fun binaryStringToHexString(value: String): String {
        val digits = (value.length + 3) / 4
        val hexChars = CharArray(digits + 2)
        hexChars[0] = '0'
        hexChars[1] = 'x'
        var position: Int = value.length - 1
        for (digs in 0..<digits) {
            var result = 0
            var pow = 1
            var rep = 0
            while (rep < 4 && position >= 0) {
                if (value[position] == '1') result += pow
                pow *= 2
                position--
                rep++
            }
            hexChars[digits - digs + 1] = chars[result]
        }
        return hexChars.concatToString()
    }

    /**
     * Translate a String consisting of hexadecimal digits into a String consisting of corresponding binary digits
     * (ones and zeroes).
     * No length limit.
     * String position zero will have the most significant bit; position length - 1 will have the least-significant bit.
     *
     * @param value String containing only hexadecimal digits.
     * Letters may be either upper or lower case.
     * Works either with or without the leading "0x".
     * @return String with equivalent value in binary.
     */
    @JvmStatic
    fun hexStringToBinaryString(value: String): String {
        return buildString {
            var value = value
            if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2)
            for (digs in value.indices) {
                append(
                    when (value[digs]) {
                        '0' -> "0000"
                        '1' -> "0001"
                        '2' -> "0010"
                        '3' -> "0011"
                        '4' -> "0100"
                        '5' -> "0101"
                        '6' -> "0110"
                        '7' -> "0111"
                        '8' -> "1000"
                        '9' -> "1001"
                        'a', 'A' -> "1010"
                        'b', 'B' -> "1011"
                        'c', 'C' -> "1100"
                        'd', 'D' -> "1101"
                        'e', 'E' -> "1110"
                        'f', 'F' -> "1111"
                        else -> continue
                    }
                )
            }
        }
    }

    /**
     * Translate a string
     * consisting of ones and zeroes into the char equivalent of the corresponding hexadecimal digit.
     * String length limited to 4.
     * String position 0 has the most-significant bit, position length - 1 has the least-significant bit.
     *
     * @param value The String value to convert.
     * @return Hexadecimal characters that are the equivalent of the decoded String. If string length > 4, returns '0'.
     */
    @JvmStatic
    fun binaryStringToHexDigit(value: String): Char {
        if (value.length > 4) return '0'
        var result = 0
        var pow = 1
        for (i in value.length - 1 downTo 0) {
            if (value[i] == '1') result += pow
            pow *= 2
        }
        return chars[result]
    }

    /**
     * Prefix a hexadecimal-indicating string "0x" to the string which is
     * returned by the method "Integer.toHexString". Prepend leading zeroes
     * to that string as necessary to make it always eight hexadecimal digits.
     *
     * @param d The int value to convert.
     * @return String containing '0', '1', ...'F' which form hexadecimal equivalent of int.
     */
    @JvmStatic
    fun intToHexString(d: Int): String {
        val leadingZero = "0"
        val leadingX = "0x"
        var t = Integer.toHexString(d)
        while (t.length < 8) t += leadingZero
        t += leadingX
        return t
    }

    /**
     * Returns a six character string representing the 16-bit hexadecimal equivalent of the
     * given integer value.
     * The first two characters are "0x".
     * It assumes the value will "fit"
     * in 16 bits.  If non-negative, prepend leading zeroes to that string as necessary
     * to make it always four hexadecimal digits.  If negative, chop off the first
     * four 'f' digits so the result is always four hexadecimal digits
     *
     * @param d The int value to convert.
     * @return String containing '0', '1', ...'F' which form hexadecimal equivalent of int.
     */
    @JvmStatic
    fun intToHalfHexString(d: Int): String {
        val leadingZero = "0"
        val leadingX = "0x"
        var t = Integer.toHexString(d)
        if (t.length > 4) t = t.substring(t.length - 4)
        while (t.length < 4) t += leadingZero
        t += leadingX
        return t
    }

    /**
     * Prefix a hexadecimal-indicating string "0x" to the string equivalent to the
     * hexadecimal value in the long parameter. Prepend leading zeroes
     * to that string as necessary to make it always sixteen hexadecimal digits.
     *
     * @param value The long value to convert.
     * @return String containing '0', '1', ...'F' which form hexadecimal equivalent of long.
     */
    @JvmStatic
    fun longToHexString(value: Long): String =
        binaryStringToHexString(longToBinaryString(value))

    /**
     * Produce String equivalent of integer value interpreting it as an unsigned integer.
     * For instance, -1 (0xffffffff) produces "4294967295" instead of "-1".
     *
     * @param d The int value to interpret.
     * @return String which forms unsigned 32 bit equivalent of int.
     */
    @JvmStatic
    fun unsignedIntToIntString(d: Int): String =
        if ((d >= 0)) d.toString() else (UNSIGNED_BASE + d).toString()

    /**
     * Produce ASCII string equivalent of integer value, interpreting it as 4 one-byte
     * characters.  If the value in a given byte does not correspond to a printable
     * character, it will be assigned a default character (defined in config.properties)
     * for a placeholder.
     *
     * @param d The int value to interpret
     * @return String that represents ASCII equivalent
     */
    @JvmStatic
    fun intToAscii(d: Int): String {
        val result = StringBuilder(8)
        for (i in 3 downTo 0) {
            val byteValue = getByte(d, i)
            result.append(if ((byteValue < Globals.ASCII_TABLE.size)) Globals.ASCII_TABLE[byteValue] else Globals.ASCII_NON_PRINT)
        }
        return result.toString()
    }

    /**
     * Attempt to validate the given string whose characters represent a 32-bit integer.
     * Integer.decode() is insufficient because it will not allow incorporation of
     * hex two's complement (i.e., 0x80...0 through 0xff...f).  Allows
     * optional negative (-) sign but no embedded spaces.
     *
     * @param s candidate string
     * @return returns int value represented by given string
     * @throws NumberFormatException if the string cannot be translated into an int
     */
    @JvmStatic
    fun stringToInt(s: String): Int {
        var work = s
        var result = 0
        // First, use Integer.decode().  This will validate most, but it flags
        // valid hex two's complement values as exceptions.  We'll catch those and
        // do our own validation.
        try {
            result = Integer.decode(s)
        } catch (nfe: NumberFormatException) {
            // Multistep process toward validation of hex two's complement. 3-step test:
            //   (1) exactly 10 characters long,
            //   (2) starts with Ox or 0X,
            //   (3) last 8 characters are valid hex digits.
            work = work.lowercase(Locale.getDefault())
            if (work.length == 10 && work.startsWith("0x")) {
                val bitString = StringBuilder()
                var index: Int
                // while testing characters, build bit string to set up for binaryStringToInt
                var i = 2
                while (i < 10) {
                    index = Arrays.binarySearch(chars, work[i])
                    if (index < 0) {
                        throw NumberFormatException()
                    }
                    bitString.append(intToBinaryString(index, 4))
                    i++
                }
                result = binaryStringToInt(bitString.toString())
            } else if (!work.startsWith("0x")) {
                var i = 0
                while (i < work.length) {
                    val c = work[i]
                    if (c in '0'..'9') {
                        result *= 10
                        result += c.code - '0'.code
                    } else {
                        throw NumberFormatException()
                    }
                    i++
                }
            } else {
                throw NumberFormatException()
            }
        }
        return result
    }

    /**
     * Attempt to validate given string whose characters represent a 64 bit long.
     * Long.decode() is insufficient because it will not allow incorporation of
     * hex two's complement (i.e., 0x80...0 through 0xff...f).  Allows
     * optional negative (-) sign but no embedded spaces.
     *
     * @param s candidate string
     * @return returns long value represented by given string
     * @throws NumberFormatException if string cannot be translated into a long
     */
    @JvmStatic
    fun stringToLong(s: String): Long {
        var work = s
        var result: Long
        // First, use Long.decode().  This will validate most, but it flags
        // valid hex two's complement values as exceptions.  We'll catch those and
        // do our own validation.
        try {
            result = s.decodeToLong()
        } catch (nfe: NumberFormatException) {
            // Multistep process toward validation of hex two's complement. 3-step test:
            //   (1) exactly 18 characters long,
            //   (2) starts with Ox or 0X,
            //   (3) last 16 characters are valid hex digits.
            work = work.lowercase(Locale.getDefault())
            if (work.length == 18 && work.startsWith("0x")) {
                val bitString = StringBuilder()
                var index: Int
                // while testing characters, build bit string to set up for binaryStringToInt
                var i = 2
                while (i < 18) {
                    index = Arrays.binarySearch(chars, work[i])
                    if (index < 0) {
                        throw NumberFormatException()
                    }
                    bitString.append(intToBinaryString(index, 4))
                    i++
                }
                result = binaryStringToLong(bitString.toString())
            } else {
                throw NumberFormatException()
            }
        }
        return result
    }

    /**
     * Returns int representing the bit values of the high order 32 bits of given
     * 64-bit long value.
     *
     * @param longValue The long value from which to extract bits.
     * @return int containing high order 32 bits of argument
     */
    @JvmStatic
    fun highOrderLongToInt(longValue: Long): Int =
        (longValue shr 32).toInt() // high-order 32 bits

    /**
     * Returns int representing the bit values of the low order 32 bits of given
     * 64-bit long value.
     *
     * @param longValue The long value from which to extract bits.
     * @return int containing low order 32 bits of argument
     */
    @JvmStatic
    fun lowOrderLongToInt(longValue: Long): Int =
        (longValue shl 32 shr 32).toInt() // low-order 32 bits

    /**
     * Returns long (64-bit integer) combining the bit values of two given 32-bit
     * integer values.
     *
     * @param highOrder Integer to form the high-order 32 bits of a result.
     * @param lowOrder  Integer to form the high-order 32 bits of a result.
     * @return long containing concatenated 32-bit int values.
     */
    @JvmStatic
    fun twoIntegersToLong(highOrder: Int, lowOrder: Int): Long =
        ((highOrder.toLong()) shl 32) or ((lowOrder.toLong()) and 0xFFFFFFFFL)

    /**
     * Returns the bit value at the given position in the given int value.
     *
     * @param value The value to read the bit from.
     * @param bit   The bit position in range 0 (least significant) to 31 (most)
     * @return Zero if the bit position contains 0, and 1 otherwise.
     */
    @JvmStatic
    fun bitValue(value: Int, bit: Int): Int = 1 and (value shr bit)

    /**
     * Returns the bit value at the given bit position of the given long value.
     *
     * @param value The value to read the bit from.
     * @param bit   The bit position in range 0 (least significant) to 63 (most)
     * @return Zero if the bit position contains 0, and 1 otherwise.
     */
    @JvmStatic
    private fun bitValue(value: Long, bit: Int): Long = 1L and (value shr bit)

    /**
     * Sets the specified bit of the specified value to 1, and returns the result.
     *
     * @param value The value in which the bit is to be set.
     * @param bit   The bit position in range 0 (least significant) to 31 (most)
     * @return value possibly modified with given bit set to 1.
     */
    @JvmStatic
    fun setBit(value: Int, bit: Int): Int = value or (1 shl bit)

    /**
     * Sets the specified bit of the specified value to 0, and returns the result.
     *
     * @param value The value in which the bit is to be set.
     * @param bit   The bit position in range 0 (least significant) to 31 (most)
     * @return value possibly modified with given bit set to 0.
     */
    @JvmStatic
    fun clearBit(value: Int, bit: Int): Int = value and (1 shl bit).inv()

    /**
     * Sets the specified byte of the specified value to the low-order eight bits of
     * the specified replacement value, and returns the result.
     *
     * @param value   The value in which the byte is to be set.
     * @param bite    byte position in range 0 (least significant) to 3 (most)
     * @param replace value to place into that byte position - use low-order eight bits
     * @return value modified value.
     */
    @JvmStatic
    fun setByte(value: Int, bite: Int, replace: Int): Int =
        value and (0xFF shl (bite shl 3)).inv() or ((replace and 0xFF) shl (bite shl 3))

    /**
     * Gets the specified byte of the specified value.
     *
     * @param value The value in which the byte is to be retrieved.
     * @param bite  byte position in range 0 (least significant) to 3 (most)
     * @return zero-extended byte value in low order byte.
     */
    @JvmStatic
    fun getByte(value: Int, bite: Int): Int = value shl ((3 - bite) shl 3) ushr 24

    /**
     * Parsing method to see if a string represents a hex number.
     * As per [...](http://java.sun.com/j2se/1.4.2/docs/api/java/lang/Integer.html#decode(java.lang.String)),
     * a string represents a hex number if the string is in the forms:
     * Signopt 0x HexDigits
     * Signopt 0X HexDigits
     * Signopt # HexDigits <---- Disallow this form since # is MIPS comment
     *
     * @param v String containing numeric digits (could be decimal, octal, or hex)
     * @return Returns <tt>true</tt> if string represents a hex number, else returns <tt>false</tt>.
     */
    @JvmStatic
    fun isHex(v: String): Boolean {
        try {
            // don't care about return value, just whether it threw exception.
            // If value is EITHER a valid int OR a valid long, continue.
            try {
                stringToInt(v)
            } catch (nfe: NumberFormatException) {
                try {
                    stringToLong(v)
                } catch (e: NumberFormatException) {
                    return false // both failed; it is neither a valid int nor long
                }
            }
            if ((v[0] == '-') &&  // sign is optional, but if present can only be negative
                (v[1] == '0') && (v[1].uppercaseChar() == 'X')
            ) return true // The form is Sign 0x… and the entire string is parseable as a number
            else if ((v[0] == '0') && (v[1].uppercaseChar() == 'X')) return true // Form is 0x… and the entire string is parseable as a number
        } catch (e: StringIndexOutOfBoundsException) {
            return false
        }
        return false // default
    }

    /**
     * Parsing method to see if a string represents an octal number.
     * As per [...](http://java.sun.com/j2se/1.4.2/docs/api/java/lang/Integer.html#decode(java.lang.String)),
     * a string represents an octal number if the string is in the forms:
     * Signopt 0 OctalDigits
     *
     * @param v String containing numeric digits (could be decimal, octal, or hex)
     * @return Returns <tt>true</tt> if string represents an octal number, else returns <tt>false</tt>.
     */
    @JvmStatic
    fun isOctal(v: String): Boolean {
        // Don't mistake "0" or a string that starts "0x" for an octal string
        try {
            // we don't care what value Binary.stringToInt(v) returns, just whether it threw exception
            stringToInt(v)
            if (isHex(v)) return false // String starts with "0" but continues "0x", so not octal
            // sign is optional but if present can only be negative
            if (v[0] == '-' && v[1] == '0') // Has to have more digits than the leading zero
                return true // The form is Sign 0… and the entire string is parseable as a number
            else if ((v[0] == '0') && (v.length > 1)) // Has to have more digits than the leading zero
                return true // Form is 0… and the entire string is parseable as a number
        } catch (e: StringIndexOutOfBoundsException) {
            return false
        } catch (e: NumberFormatException) {
            return false
        }
        return false // default
    }
}