package edu.missouristate.mars.util

import edu.missouristate.mars.ApplicationInfo
import java.lang.Integer.decode as decodeInt
import java.lang.Long.decode as decodeLong

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Binary representations and numeric spellings accepted by MARS.
 *
 * @author Pete Sanderson, Ken Vollmar, and Jason Bumgarner
 */
open class Binary {
    companion object {
        private const val HEX_DIGITS = "0123456789abcdef"

        @JvmStatic
        fun intToBinaryString(value: Int, length: Int): String =
            CharArray(length) { if (bitValue(value, length - 1 - it) == 1) '1' else '0' }.concatToString()

        @JvmStatic
        fun intToBinaryString(value: Int): String = intToBinaryString(value, Int.SIZE_BITS)

        @JvmStatic
        fun longToBinaryString(value: Long, length: Int): String =
            CharArray(length) { if (bitValue(value, length - 1 - it) == 1) '1' else '0' }.concatToString()

        @JvmStatic
        fun longToBinaryString(value: Long): String = longToBinaryString(value, Long.SIZE_BITS)

        @JvmStatic
        fun binaryStringToInt(value: String): Int {
            var result = value[0] - '0'
            for (index in 1 until value.length) result = (result shl 1) or (value[index] - '0')
            return result
        }

        @JvmStatic
        fun binaryStringToLong(value: String): Long {
            var result = (value[0] - '0').toLong()
            for (index in 1 until value.length) result = (result shl 1) or (value[index] - '0').toLong()
            return result
        }

        @JvmStatic
        fun binaryStringToHexString(value: String): String {
            val digits = (value.length + 3) / 4
            val result = CharArray(digits + 2)
            result[0] = '0'
            result[1] = 'x'
            var position = value.lastIndex
            for (digit in result.lastIndex downTo 2) {
                var nibble = 0
                for (bit in 0..3) {
                    if (position < 0) break
                    if (value[position--] == '1') nibble = nibble or (1 shl bit)
                }
                result[digit] = HEX_DIGITS[nibble]
            }
            return result.concatToString()
        }

        @JvmStatic
        fun hexStringToBinaryString(value: String): String = buildString {
            val firstDigit = if (hasHexPrefix(value)) 2 else 0
            for (index in firstDigit until value.length) {
                val digit = asciiHexDigit(value[index])
                if (digit >= 0) append(intToBinaryString(digit, 4))
            }
        }

        @JvmStatic
        fun binaryStringToHexDigit(value: String): Char {
            if (value.length > 4) return '0'
            var digit = 0
            for (character in value) digit = (digit shl 1) or if (character == '1') 1 else 0
            return HEX_DIGITS[digit]
        }

        @JvmStatic
        fun intToHexString(value: Int): String = "0x" + value.toUInt().toString(16).padStart(8, '0')

        @JvmStatic
        fun intToHalfHexString(value: Int): String = "0x" + (value and 0xffff).toString(16).padStart(4, '0')

        @JvmStatic
        fun longToHexString(value: Long): String = "0x" + value.toULong().toString(16).padStart(16, '0')

        @JvmStatic
        fun unsignedIntToIntString(value: Int): String = value.toUInt().toString()

        @JvmStatic
        fun intToAscii(value: Int): String = buildString(8) {
            for (index in 3 downTo 0) {
                val byte = getByte(value, index)
                append(if (byte < ApplicationInfo.ASCII_TABLE.size) ApplicationInfo.ASCII_TABLE[byte] else ApplicationInfo.ASCII_NON_PRINT)
            }
        }

        /** Accept JDK integer syntax, full-width hexadecimal words, and decimal words that wrap at 32 bits. */
        @JvmStatic
        fun stringToInt(value: String): Int = try {
            decodeInt(value)
        } catch (_: NumberFormatException) {
            if (hasHexPrefix(value)) {
                parseHexBits(value, 8).toInt()
            } else {
                // Preserve decimal overflow behavior for assembler data and address spellings.
                var result = 0
                for (character in value) {
                    if (character !in '0'..'9') throw NumberFormatException()
                    result = result * 10 + (character - '0')
                }
                result
            }
        }

        /** Accept JDK long syntax and full-width hexadecimal words. */
        @JvmStatic
        fun stringToLong(value: String): Long = try {
            decodeLong(value)
        } catch (_: NumberFormatException) {
            if (!hasHexPrefix(value)) throw NumberFormatException()
            parseHexBits(value, 16)
        }

        private fun parseHexBits(value: String, digits: Int): Long {
            if (value.length != digits + 2) throw NumberFormatException()
            var result = 0L
            for (index in 2 until value.length) {
                val digit = asciiHexDigit(value[index])
                if (digit < 0) throw NumberFormatException()
                result = (result shl 4) or digit.toLong()
            }
            return result
        }

        private fun asciiHexDigit(character: Char): Int = when (character) {
            in '0'..'9' -> character - '0'
            in 'a'..'f' -> character - 'a' + 10
            in 'A'..'F' -> character - 'A' + 10
            else -> -1
        }

        private fun hasHexPrefix(value: String): Boolean = value.startsWith("0x") || value.startsWith("0X")

        @JvmStatic
        fun highOrderLongToInt(value: Long): Int = (value shr Int.SIZE_BITS).toInt()

        @JvmStatic
        fun lowOrderLongToInt(value: Long): Int = value.toInt()

        @JvmStatic
        fun twoIntsToLong(highOrder: Int, lowOrder: Int): Long =
            (highOrder.toLong() shl Int.SIZE_BITS) or lowOrder.toUInt().toLong()

        @JvmStatic
        fun bitValue(value: Int, bit: Int): Int = (value shr bit) and 1

        @JvmStatic
        fun bitValue(value: Long, bit: Int): Int = ((value shr bit) and 1L).toInt()

        @JvmStatic
        fun setBit(value: Int, bit: Int): Int = value or (1 shl bit)

        @JvmStatic
        fun clearBit(value: Int, bit: Int): Int = value and (1 shl bit).inv()

        @JvmStatic
        fun setByte(value: Int, byte: Int, replacement: Int): Int {
            val shift = byte shl 3
            return (value and (0xff shl shift).inv()) or ((replacement and 0xff) shl shift)
        }

        @JvmStatic
        fun getByte(value: Int, byte: Int): Int = (value ushr (byte shl 3)) and 0xff

        @JvmStatic
        fun isHex(value: String): Boolean {
            if (!hasHexPrefix(value)) return false
            return try {
                stringToInt(value)
                true
            } catch (_: NumberFormatException) {
                try {
                    stringToLong(value)
                    true
                } catch (_: NumberFormatException) {
                    false
                }
            }
        }

        @JvmStatic
        fun isOctal(value: String): Boolean {
            if (value.length <= 1 || isHex(value)) return false
            if (value[0] != '0' && !value.startsWith("-0")) return false
            return try {
                stringToInt(value)
                true
            } catch (_: NumberFormatException) {
                false
            }
        }
    }
}
