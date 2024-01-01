/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
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

@file:Suppress("MemberVisibilityCanBePrivate")

package edu.missouristate.mars.assembler

/** Information about MIPS data types. */
object DataTypes {
    /** The number of bytes occupied by MIPS double is 8. */
    const val DOUBLE_SIZE = 8

    /** The number of bytes occupied by MIPS float is 4. */
    const val FLOAT_SIZE = 4

    /** The number of bytes occupied by MIPS word is 4. */
    const val WORD_SIZE = 4

    /** The number of bytes occupied by MIPS half-word is 2. */
    const val HALF_SIZE = 2

    /** The number of bytes occupied by MIPS byte is 1. */
    const val BYTE_SIZE = 1

    /** The number of bytes occupied by MIPS char is 1. */
    const val CHAR_SIZE = 1

    /**
     * The maximum value that can be stored in a MIPS word is $2^{31}-1$.
     *
     * @usesMathJax
     */
    const val MAX_WORD_VALUE = Int.MAX_VALUE

    /**
     * The minimum value that can be stored in a MIPS word is $-2^{31}$.
     *
     * @usesMathJax
     */
    const val MIN_WORD_VALUE = Int.MIN_VALUE

    /**
     * The maximum value that can be stored in a MIPS half-word is $2^{15}-1$.
     *
     * @usesMathJax
     */
    const val MAX_HALF_VALUE = 32_767

    /**
     * The minimum value that can be stored in a MIPS half-word is $-2^{15}$.
     *
     * @usesMathJax
     */
    const val MIN_HALF_VALUE = -32_768

    /**
     * The maximum value that can be stored in an unsigned MIPS half-word is $2^{16}-1$.
     *
     * @usesMathJax
     */
    const val MAX_UHALF_VALUE = 65_535

    /**
     * The minimum value that can be stored in an unsigned MIPS half-word is 0.
     */
    const val MIN_UHALF_VALUE = 0

    /**
     * The maximum value that can be stored in a MIPS byte is $2^7-1$.
     *
     * @usesMathJax
     */
    const val MAX_BYTE_VALUE = Byte.MAX_VALUE

    /**
     * The minimum value that can be stored in a MIPS byte is $-2^7-1$.
     *
     * @usesMathJax
     */
    const val MIN_BYTE_VALUE = Byte.MIN_VALUE

    /**
     * The maximum positive finite value that can be stored in a MIPS float is $(2-2^{-23}) \times 2^{127}$.
     *
     * @usesMathJax
     */
    const val MAX_FLOAT_VALUE = Float.MAX_VALUE

    /**
     * The minimum magnitude negative value that can be stored in a MIPS float is $-((2-2^{-23}) \times 2^{127})$.
     *
     * @usesMathJax
     */
    const val MIN_FLOAT_VALUE = -Float.MAX_VALUE

    @Deprecated(
        "Renamed to MIN_FLOAT_VALUE.",
        ReplaceWith("MIN_FLOAT_VALUE"),
        DeprecationLevel.ERROR
    )
    const val LOW_FLOAT_VALUE = MIN_FLOAT_VALUE

    /**
     * The maximum positive finite value that can be stored in a MIPS double is $(2-2^{-52}) \times 2^{1023}$.
     *
     * @usesMathJax
     */
    const val MAX_DOUBLE_VALUE = Double.MAX_VALUE

    /**
     * The largest magnitude negative value that can be stored in a MIPS double is $-((2-2^{-52}) \times 2^{1023})$.
     *
     * @usesMathJax
     */
    const val MIN_DOUBLE_VALUE = -Double.MAX_VALUE

    @Deprecated(
        "Renamed to MIN_DOUBLE_VALUE.",
        ReplaceWith("MIN_DOUBLE_VALUE"),
        DeprecationLevel.ERROR
    )
    const val LOW_DOUBLE_VALUE = MIN_DOUBLE_VALUE

    /**
     * Get the length in bytes for numeric MIPS directives.
     *
     * @param direct The [Directives] item to be measured.
     * @return The length in bytes for values of that type. If the type is not numeric, or is not implemented yet, it
     * returns 0.
     */
    @JvmStatic
    fun getLengthInBytes(direct: Directives): Int = when (direct) {
        Directives.FLOAT -> FLOAT_SIZE
        Directives.DOUBLE -> DOUBLE_SIZE
        Directives.WORD -> WORD_SIZE
        Directives.HALF -> HALF_SIZE
        Directives.BYTE -> BYTE_SIZE
        else -> 0
    }

    // These two methods are written in a rather confusing/misleading way.
    // The original Javadoc said they checked if the value was in range, but the names and actions are the exact
    // opposite of the Javadoc; maybe these methods are always inverted?
    // Either way, I corrected the return marker to make it more accurate.

    /**
     * Determines whether a given integer value falls outside the value range for the given directive.
     *
     * @param direct The [Directives] value that controls the storage allocation for the value.
     * @param value The value to be stored.
     *
     * @return Returns `true` if the value cannot be stored in the number of bytes allowed by the given directive
     * (`.word`, `.half`, `.byte`); `false` if it can be stored in the number of bytes allowed by the given directive.
     */
    @JvmStatic
    fun outOfRange(direct: Directives, value: Int): Boolean = when (direct) {
        Directives.HALF -> (value < MIN_HALF_VALUE || value > MAX_HALF_VALUE)
        Directives.BYTE -> (value < MIN_BYTE_VALUE || value > MAX_BYTE_VALUE)
        else -> false
    }

    /**
     * Determines whether the given floating point value falls within the value range for the given directive.
     * For floats, this refers to the range of the data type, not precision.
     * For example, 1.23456789012345 can be stored in a float with a loss of precision, as it's in range.
     * But 1.23e500 cannot be stored in a float because the exponent 500 is too large;
     * floats allow eight bits for the exponent.
     *
     * @param direct The [Directives] value that controls storage allocation for the value.
     * @param value The value to be stored.
     *
     * @return `true` if the value is not within range of the given directive; `false` otherwise.
     */
    @JvmStatic
    fun outOfRange(direct: Directives, value: Double): Boolean = when (direct) {
        Directives.FLOAT -> value < MIN_FLOAT_VALUE || value > MAX_FLOAT_VALUE
        Directives.DOUBLE -> value < MIN_DOUBLE_VALUE || value > MAX_FLOAT_VALUE
        else -> false
    }
}