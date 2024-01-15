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

package edu.missouristate.mars.venus

import edu.missouristate.mars.*
import edu.missouristate.mars.util.Binary.highOrderLongToInt
import edu.missouristate.mars.util.Binary.intToAscii
import edu.missouristate.mars.util.Binary.intToHexString
import edu.missouristate.mars.util.Binary.longToHexString
import edu.missouristate.mars.util.Binary.lowOrderLongToInt
import edu.missouristate.mars.util.Binary.unsignedIntToIntString
import java.awt.event.ActionEvent
import java.awt.event.ItemEvent
import javax.swing.JCheckBox
import javax.swing.JCheckBoxMenuItem

class NumberDisplayBaseChooser(text: String, displayInHex: Boolean) : JCheckBox(text, displayInHex) {
    companion object {
        const val DECIMAL = 10
        const val HEXADECIMAL = 16
        const val ASCII = 0

        @JvmStatic
        fun getBase(setting: Boolean) = if (setting) HEXADECIMAL else DECIMAL

        /**
         * Produces a string form of an unsigned given the value and the
         * numerical base to convert it to.  This class
         * method can be used by anyone anytime.  If base is 16, result
         * is same as for formatNumber().  If base is 10, will produce
         * string version of unsigned value.  E.g. 0xffffffff will produce
         * "4294967295" instead of "-1".
         *
         * @param value the number to be converted
         * @param base  the numerical base to use (currently 10 or 16)
         * @return a String equivalent of the value rendered appropriately.
         */
        @JvmStatic
        fun formatUnsignedInteger(value: Int, base: Int): String =
            if (base == HEXADECIMAL) intToHexString(value) else unsignedIntToIntString(value)

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
            HEXADECIMAL -> intToHexString(value)
            ASCII -> intToAscii(value)
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
            if (base == HEXADECIMAL) intToHexString(value.toIntBits()) else value.toString()

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
                intToHexString(highOrderLongToInt(bits)) + intToHexString(lowOrderLongToInt(bits)).substring(2)
            } else value.toString()

        /**
         * Produces a string form of a float given an integer containing
         * the 32 bit pattern and the numerical base to use (10 or 16).  If the
         * base is 16, the string will be built from the 32 bits.  If the
         * base is 10, the int bits will be converted to float and the
         * string constructed from that.  Seems an odd distinction to make,
         * except that contents of floating point registers are stored
         * internally as int bits.  If the int bits represent a NaN value
         * (of which there are many!), converting them to float then calling
         * formatNumber(float, int) above, causes the float value to become
         * the canonical NaN value 0x7fc00000.  It does not preserve the bit
         * pattern!  Then converting it to hex string yields the canonical NaN.
         * Not an issue if display base is 10 since result string will be NaN
         * no matter what the internal NaN value is.
         *
         * @param value the int bits to be converted to string of corresponding float.
         * @param base  the numerical base to use (currently 10 or 16)
         * @return a String equivalent of the value rendered appropriately.
         */
        @JvmStatic
        fun formatFloatNumber(value: Int, base: Int): String =
            if (base == HEXADECIMAL) intToHexString(value) else value.bitsToFloat().toString()

        /**
         * Produces a string form of a double given a long containing
         * the 64 bit pattern and the numerical base to use (10 or 16).  If the
         * base is 16, the string will be built from the 64 bits.  If the
         * base is 10, the long bits will be converted to double and the
         * string constructed from that.  Seems an odd distinction to make,
         * except that contents of floating point registers are stored
         * internally as int bits.  If the int bits represent a NaN value
         * (of which there are many!), converting them to double then calling
         * formatNumber(double, int) above, causes the double value to become
         * the canonical NaN value.  It does not preserve the bit
         * pattern!  Then converting it to hex string yields the canonical NaN.
         * Not an issue if display base is 10 since result string will be NaN
         * no matter what the internal NaN value is.
         *
         * @param value the long bits to be converted to string of corresponding double.
         * @param base  the numerical base to use (currently 10 or 16)
         * @return a String equivalent of the value rendered appropriately.
         */
        @JvmStatic
        fun formatDoubleNumber(value: Long, base: Int): String =
            if (base == HEXADECIMAL) longToHexString(value) else value.bitsToDouble().toString()
    }

    var base: Int = HEXADECIMAL
        set(value) {
            if (value == DECIMAL || value == HEXADECIMAL) field = value
        }

    var settingsMenuItem: JCheckBoxMenuItem? = null

    init {
        base = getBase(displayInHex)
        addItemListener {
            val choice = it.item as NumberDisplayBaseChooser
            choice.base = if (it.stateChange == ItemEvent.SELECTED) HEXADECIMAL else DECIMAL
            if (settingsMenuItem != null) {
                settingsMenuItem?.isSelected = choice.isSelected
                val listeners = settingsMenuItem?.actionListeners
                val event = ActionEvent(settingsMenuItem, 0, "chooser")
                for (listener in listeners ?: arrayOf()) listener.actionPerformed(event)
            }
            Globals.gui?.mainPane?.executePane?.numberDisplayBaseChanged(choice)
        }
    }

    /**
     * Produces a string form of a number given the value.  There
     * is also an class (static method) that uses a specified
     * base.
     *
     * @param value the number to be converted
     * @return a String equivalent of the value rendered appropriately.
     */
    fun formatNumber(value: Int): String =
        if (base == HEXADECIMAL) intToHexString(value) else value.toString()

    /**
     * Produces a string form of an unsigned integer given the value.  There
     * is also an class (static method) that uses a specified base.
     * If the current base is 16, this produces the same result as formatNumber().
     *
     * @param value the number to be converted
     * @return a String equivalent of the value rendered appropriately.
     */
    fun formatUnsignedInteger(value: Int) = formatUnsignedInteger(value, base)
}