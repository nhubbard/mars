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

package edu.missouristate.mars.tools

import java.util.Vector
import javax.swing.table.AbstractTableModel

/**
 * Simulates the actual functionality of a Branch History Table (BHT).
 *
 * The BHT consists of a number of BHT entries which are used to perform branch prediction.
 * The entries of the BHT are stored as a Vector of BHTEntry objects.
 * The number of entries is configurable but has to be a power of 2.
 * The history kept by each BHT entry is also configurable during run-time.
 * A change of the configuration, however, causes a complete reset of the BHT.
 *
 * The typical interaction is as follows:
 *
 * - Construction of a BHT with a certain number of entries with a given history size.
 * - When encountering a branch instruction, the index of the relevant BHT entry is calculated via the
 *   [getIndexForAddress] method.
 * - The current prediction of the BHT entry at the calculated index is obtained via the
 *   [getPredictionAt] method.
 * - After detecting if the branch was really taken or not, this feedback is provided to the BHT by the
 *   [updatePredictionAt] method.
 *
 * Additionally, it serves as a TableModel that can be directly used to render the state of the BHT in a JTable.
 * Feedback provided to the BHT causes a change of the internal state and a repaint of the table(s)
 * associated with this model.
 *
 * @author ingo.kofler@itec.uni-klu.ac.at
 *
 * @param numEntries The number of entries in the BHT.
 * @param historySize The size of the history (in bits/number of past branches)
 */
class BHTableModel(
    numEntries: Int,
    historySize: Int,
    initVal: Boolean
) : AbstractTableModel() {
    /** Vector holding the entries of the BHT. */
    private lateinit var entries: Vector<BHTEntry>

    /** Number of entries in the BHT. */
    private var entryCount: Int = 0

    /** Table columns. */
    private val columnNames = arrayOf(
        "Index",
        "History",
        "Prediction",
        "Correct",
        "Incorrect",
        "Precision"
    )

    /** Table column types. */
    private val columnClasses = arrayOf(
        Int::class.java,
        String::class.java,
        String::class.java,
        Int::class.java,
        Int::class.java,
        Double::class.java
    )

    init {
        initBHT(numEntries, historySize, initVal)
    }

    /**
     * Returns the [i]th name column of the table. Required by the AbstractTableModel superclass.
     */
    override fun getColumnName(i: Int): String =
        columnNames.getOrNull(i) ?:
            throw IllegalArgumentException("Illegal column index $i (must be in range ${columnNames.indices})")

    /**
     * Returns the [i]th column class/type of the table. Required by the AbstractTableModel superclass.
     */
    override fun getColumnClass(i: Int): Class<*> =
        columnClasses.getOrNull(i) ?:
            throw IllegalArgumentException("Illegal column index $i (must be in range ${columnClasses.indices}")

    /**
     * Get the number of columns. Required by the AbstractTableModel superclass.
     */
    override fun getColumnCount(): Int = 6

    /**
     * Get the number of entries in the BHT. Required by the AbstractTableModel superclass.
     */
    override fun getRowCount(): Int = entryCount

    /**
     * Get the value of the cell at the given row and column. Required by the AbstractTableModel superclass.
     */
    override fun getValueAt(row: Int, col: Int): Any {
        val e = entries.elementAt(row) ?: return ""
        return when (col) {
            0 -> row
            1 -> e.predictionHistory
            2 -> e.currentPrediction
            3 -> e.correctPredictions
            4 -> e.incorrectPredictions
            5 -> e.predictionRatio
            else -> ""
        }
    }

    /**
     * Initializes the BHT with the given size and history.
     * All previous data like the BHT entry history and statistics will get lost.
     * A refresh of the table that uses this BHT as a model will be triggered.
     */
    fun initBHT(numEntries: Int, historySize: Int, initVal: Boolean) {
        if (numEntries <= 0 || (numEntries and (numEntries - 1)) != 0)
            throw IllegalArgumentException("Number of entries must be a positive power of 2!")
        if (historySize < 1 || historySize > 2)
            throw IllegalArgumentException("Only history sizes of 1 or 2 are supported!")
        entryCount = numEntries
        entries = Vector()
        for (i in 0..<entryCount) entries.add(BHTEntry(historySize, initVal))
        fireTableStructureChanged()
    }

    /**
     * Returns the index into the BHT for a given branch instruction address.
     * A simple direct mapping is used.
     *
     * @param address the address of the branch instruction
     * @return the index into the BHT
     */
    fun getIndexForAddress(address: Int): Int {
        if (address < 0) throw IllegalArgumentException("Negative addresses are not valid inputs!")
        return (address shr 2) % entryCount
    }

    /**
     * Retrieve the prediction for the i-th BHT entry.
     *
     * @param index the index of the entry in the BHT
     * @return the prediction to take (true) or do not take (false) the branch
     */
    fun getPredictionAt(index: Int): Boolean {
        if (index !in entries.indices)
            throw IllegalArgumentException("Only indexes in the range ${entries.indices} are allowed!")
        return entries.elementAt(index).prediction
    }

    /**
     * Updates the BHT entry with the outcome of the branch instruction. This causes a change in the model and signals
     * to update the connected table(s).
     *
     * @param index The index of the entry in the BHT.
     */
    fun updatePredictionAt(index: Int, branchTaken: Boolean) {
        if (index !in entries.indices)
            throw IllegalArgumentException("Only indexes in the range ${entries.indices} are allowed!")
        entries.elementAt(index).updatePrediction(branchTaken)
        fireTableRowsUpdated(index, index)
    }
}