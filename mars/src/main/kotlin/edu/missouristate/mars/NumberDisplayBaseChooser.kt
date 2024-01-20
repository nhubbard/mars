/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars

import edu.missouristate.mars.util.Binary

object NumberDisplayBaseChooser {
    const val DECIMAL = 10
    const val HEXADECIMAL = 16
    const val ASCII = 0

    @JvmStatic
    fun getBase(setting: Boolean) = if (setting) HEXADECIMAL else DECIMAL

    /**
     * Produces a string form of an unsigned int given the value and the
     * numerical base to convert it to. This class
     * method can be used by anyone anytime. If the base is 16, the result
     * is the same as for formatNumber(). If the base is 10, it will produce
     * a string version of unsigned value. E.g. 0xffffffff will produce
     * "4294967295" instead of "-1".
     *
     * @param value the number to be converted
     * @param base  the numerical base to use (currently 10 or 16)
     * @return a String equivalent of the value rendered appropriately.
     */
    @JvmStatic
    fun formatUnsignedInteger(value: Int, base: Int): String =
        if (base == HEXADECIMAL) Binary.intToHexString(value) else Binary.unsignedIntToIntString(value)

    /**
     * Produces a string form of an integer given the value and the
     * numerical base to convert it to.  There is an instance
     * method that uses the internally stored base.  This class
     * method can be used by anyone anytime.
     *
     * @param value the number to be converted
     * @param base  the numerical base to use (currently 10 or 16)
     * @return a String equivalent of the value rendered appropriately.
     */
    @JvmStatic
    fun formatNumber(value: Int, base: Int) = when (base) {
        HEXADECIMAL -> Binary.intToHexString(value)
        ASCII -> Binary.intToAscii(value)
        else -> value.toString()
    }

    /**
     * Produces a string form of a float given the value and the
     * numerical base to convert it to.  There is an instance
     * method that uses the internally stored base.  This class
     * method can be used by anyone anytime.
     *
     * @param value the number to be converted
     * @param base  the numerical base to use (currently 10 or 16)
     * @return a String equivalent of the value rendered appropriately.
     */
    @JvmStatic
    fun formatNumber(value: Float, base: Int): String =
        if (base == HEXADECIMAL) Binary.intToHexString(value.toIntBits()) else value.toString()

    /**
     * Produces a string form of a double given the value and the
     * numerical base to convert it to.  There is an instance
     * method that uses the internally stored base.  This class
     * method can be used by anyone anytime.
     *
     * @param value the number to be converted
     * @param base  the numerical base to use (currently 10 or 16)
     * @return a String equivalent of the value rendered appropriately.
     */
    @JvmStatic
    fun formatNumber(value: Double, base: Int): String =
        if (base == HEXADECIMAL) {
            val bits = value.toLongBits()
            Binary.intToHexString(Binary.highOrderLongToInt(bits)) + Binary.intToHexString(
                Binary.lowOrderLongToInt(
                    bits
                )
            ).substring(2)
        } else value.toString()

    /**
     * Produces a string form of a float given an integer containing
     * the 32-bit pattern and the numerical base to use (10 or 16).  If the
     * base is 16, the string will be built from the 32 bits.  If the
     * base is 10, the int bits will be converted to float and the
     * string constructed from that. It seems like an odd distinction to make,
     * but the contents of floating point registers are stored
     * internally as int bits.  If the int bits represent a NaN value
     * (of which there are many!), converting them to a float and calling
     * formatNumber(float, int) above causes the float value to become
     * the canonical NaN value 0x7fc00000.  It does not preserve the bit
     * pattern!  Then converting it to hex string yields the canonical NaN.
     * Not an issue if display base is 10, since the result string will be NaN
     * no matter what the internal NaN value is.
     *
     * @param value the int bits to be converted to string of corresponding float.
     * @param base  the numerical base to use (currently 10 or 16)
     * @return a String equivalent of the value rendered appropriately.
     */
    @JvmStatic
    fun formatFloatNumber(value: Int, base: Int): String =
        if (base == HEXADECIMAL) Binary.intToHexString(value) else value.bitsToFloat().toString()

    /**
     * Produces a string form of a double given a long containing
     * the 64-bit pattern and the numerical base to use (10 or 16).  If the
     * base is 16, the string will be built from the 64 bits.  If the
     * base is 10, the long bits will be converted to double and the
     * string constructed from that. It seems an odd distinction to make,
     * but the contents of floating point registers are stored
     * internally as int bits.  If the int bits represent a NaN value
     * (of which there are many!), converting them to a double and calling
     * formatNumber(double, int) above causes the double value to become
     * the canonical NaN value.  It does not preserve the bit
     * pattern!  Then converting it to hex string yields the canonical NaN.
     * Not an issue if display base is 10, since the result string will be NaN
     * no matter what the internal NaN value is.
     *
     * @param value the long bits to be converted to string of corresponding double.
     * @param base  the numerical base to use (currently 10 or 16)
     * @return a String equivalent of the value rendered appropriately.
     */
    @JvmStatic
    fun formatDoubleNumber(value: Long, base: Int): String =
        if (base == HEXADECIMAL) Binary.longToHexString(value) else value.bitsToDouble().toString()
}