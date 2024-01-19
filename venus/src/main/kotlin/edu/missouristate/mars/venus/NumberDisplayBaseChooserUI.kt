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

@file:Suppress("MemberVisibilityCanBePrivate")

package edu.missouristate.mars.venus

import edu.missouristate.mars.*
import edu.missouristate.mars.NumberDisplayBaseChooser.DECIMAL
import edu.missouristate.mars.NumberDisplayBaseChooser.HEXADECIMAL
import edu.missouristate.mars.NumberDisplayBaseChooser.getBase
import edu.missouristate.mars.util.Binary
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

class NumberDisplayBaseChooserUI(text: String, displayInHex: Boolean) : JCheckBox(text, displayInHex) {
    var settingsMenuItem: JCheckBoxMenuItem? = null

    var base: Int = HEXADECIMAL
        set(value) {
            if (value == DECIMAL || value == HEXADECIMAL) field = value
        }

    /**
     * Produces a string form of a number given the value. There
     * is also a class (static method) that uses a specified base.
     *
     * @param value the number to be converted
     * @return a String equivalent of the value rendered appropriately.
     */
    fun formatNumber(value: Int): String =
        if (base == HEXADECIMAL) Binary.intToHexString(value) else value.toString()

    /**
     * Produces a string form of an unsigned integer given the value.  There
     * is also a class (static method) that uses a specified base.
     * If the current base is 16, this produces the same result as formatNumber().
     *
     * @param value the number to be converted
     * @return a String equivalent of the value rendered appropriately.
     */
    fun formatUnsignedInteger(value: Int) = NumberDisplayBaseChooser.formatUnsignedInteger(value, base)

    init {
        base = getBase(displayInHex)
        addItemListener {
            val choice = it.item as NumberDisplayBaseChooserUI
            choice.base = if (it.stateChange == ItemEvent.SELECTED) HEXADECIMAL else DECIMAL
            if (settingsMenuItem != null) {
                settingsMenuItem?.isSelected = choice.isSelected
                val listeners = settingsMenuItem?.actionListeners
                val event = ActionEvent(settingsMenuItem, 0, "chooser")
                for (listener in listeners ?: arrayOf()) listener.actionPerformed(event)
            }
            UIGlobals.gui?.mainPane?.executePane?.numberDisplayBaseChanged(choice)
        }
    }
}