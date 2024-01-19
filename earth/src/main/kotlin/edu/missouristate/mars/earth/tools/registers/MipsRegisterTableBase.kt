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

@file:Suppress("DEPRECATION", "LeakingThis")

package edu.missouristate.mars.earth.tools.registers

import com.intellij.ui.table.JBTable
import edu.missouristate.mars.CoreSettings
import edu.missouristate.mars.Globals
import edu.missouristate.mars.NumberDisplayBaseChooser.DECIMAL
import edu.missouristate.mars.NumberDisplayBaseChooser.HEXADECIMAL
import edu.missouristate.mars.NumberDisplayBaseChooser.formatNumber
import edu.missouristate.mars.earth.tools.registers.MipsRegisterTableColumns.*
import edu.missouristate.mars.mips.hardware.Register
import java.util.*
import java.util.logging.Logger
import javax.swing.table.AbstractTableModel

abstract class MipsRegisterTableBase : JBTable(), Observer {
    companion object {
        @JvmStatic private val LOG = Logger.getLogger("MIPS")
    }

    private var hexValues: Boolean = Globals.settings.getBooleanSetting(CoreSettings.DISPLAY_VALUES_IN_HEX)

    init {
        Globals.settings.addObserver(this)
        model = object : AbstractTableModel() {
            override fun getRowCount(): Int = this@MipsRegisterTableBase.rowCount

            override fun getColumnCount(): Int = 3

            override fun getValueAt(row: Int, col: Int): Any? {
                val c = entries[col]
                return when (c) {
                    NAME -> this@MipsRegisterTableBase.getName(row)
                    NUMBER -> this@MipsRegisterTableBase.getNumber(row)
                    VALUE -> this@MipsRegisterTableBase.getFormattedValue(this@MipsRegisterTableBase.getValue(row))
                }
            }

            override fun isCellEditable(row: Int, col: Int): Boolean =
                col == VALUE.ordinal

            override fun getColumnClass(col: Int) = String::class.java
        }
        updateHeader()
    }

    protected abstract fun getName(row: Int): Any?
    protected abstract fun getNumber(row: Int): Any?
    protected abstract fun getValue(row: Int): Number?

    protected fun getFormattedValue(number: Number?): Any? {
        if (number == null) return null
        val base = if (hexValues) HEXADECIMAL else DECIMAL
        if (number is Float) return formatNumber(number.toFloat(), base)
        if (number is Double) return formatNumber(number.toDouble(), base)
        return formatNumber(number.toInt(), base)
    }

    abstract override fun getRowCount(): Int

    override fun update(observable: Observable, o: Any?) {
        LOG.info("DEBUG :: notification :: obs=$observable, o=$o")
        if (observable is CoreSettings) {
            hexValues = observable.getBooleanSetting(CoreSettings.DISPLAY_VALUES_IN_HEX)
            updateRows()
        } else if (observable is Register) {
            updateRow(observable.number)
        }
    }

    fun updateRows() {
        (model as AbstractTableModel).fireTableDataChanged()
    }

    fun updateRow(row: Int) {
        (model as AbstractTableModel).fireTableRowsUpdated(row, row)
    }

    open fun updateHeader() {
        val header = tableHeader.columnModel
        for (col in entries)
            header.getColumn(col.ordinal).headerValue = col.toString()
    }
}