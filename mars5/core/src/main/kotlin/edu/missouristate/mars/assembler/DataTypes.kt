package edu.missouristate.mars.assembler

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

/** Storage sizes and range checks for MIPS numeric directives. */
object DataTypes {
    const val DOUBLE_SIZE = 8
    const val FLOAT_SIZE = 4
    const val WORD_SIZE = 4
    const val HALF_SIZE = 2
    const val BYTE_SIZE = 1
    const val CHAR_SIZE = 1
    const val MAX_HALF_VALUE = 32767
    const val MIN_HALF_VALUE = -32768
    const val MAX_UHALF_VALUE = 65535
    const val MIN_UHALF_VALUE = 0
    const val MAX_BYTE_VALUE = 127
    const val MIN_BYTE_VALUE = -128
    const val MAX_FLOAT_VALUE = 3.4028234663852886e38
    const val LOW_FLOAT_VALUE = -MAX_FLOAT_VALUE
    const val MAX_DOUBLE_VALUE = Double.MAX_VALUE

    @JvmStatic
    fun getLengthInBytes(directive: Directives?): Int = when (directive) {
        Directives.FLOAT -> FLOAT_SIZE
        Directives.DOUBLE -> DOUBLE_SIZE
        Directives.WORD -> WORD_SIZE
        Directives.HALF -> HALF_SIZE
        Directives.BYTE -> BYTE_SIZE
        else -> 0
    }

    @JvmStatic
    fun outOfRange(directive: Directives?, value: Int): Boolean = when (directive) {
        Directives.HALF -> value !in MIN_HALF_VALUE..MAX_HALF_VALUE
        Directives.BYTE -> value !in MIN_BYTE_VALUE..MAX_BYTE_VALUE
        else -> false
    }

    // An unordered NaN has no overflow or underflow.
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    @JvmStatic
    fun outOfRange(directive: Directives?, value: Double): Boolean =
        directive == Directives.FLOAT && (value < LOW_FLOAT_VALUE || value > MAX_FLOAT_VALUE)
}
