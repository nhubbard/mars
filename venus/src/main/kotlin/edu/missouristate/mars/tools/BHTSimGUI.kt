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

package edu.missouristate.mars.tools

import edu.missouristate.mars.vectorOf
import java.awt.*
import java.text.DecimalFormat
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer

/**
 * Represents the GUI of the BHT Simulator tool.
 *
 * The GUI consists of four parts:
 *
 * 1. A configuration panel to select the number of entries and history size
 * 2. An information panel that displays the most recent branch instruction, including its address and BHT index
 * 3. A table representing the BHT with all entries and their internal state and statistics
 * 4. A log panel that summarizes the predictions in a textual form
 */
class BHTSimGUI : JPanel() {
    companion object {
        /** The color of the current BHT entry. */
        @JvmField val COLOR_PRE_PREDICTION: Color = Color.yellow

        /** The color of a correct prediction. */
        @JvmField val COLOR_PREDICTION_CORRECT: Color = Color.green

        /** The color of an incorrect prediction. */
        @JvmField val COLOR_PREDICTION_INCORRECT: Color = Color.red

        /** The string constant representing a taken branch. */
        const val BHT_TAKE_BRANCH = "Taken"

        /** The string constant representing a non-taken branch. */
        const val BHT_DO_NOT_TAKE_BRANCH = "Not Taken"
    }

    /** A text field representing the most recent branch instruction. */
    lateinit var instructionField: JTextField
        private set

    /** A text field representing the address of the most recent branch instruction. */
    lateinit var addressField: JTextField
        private set

    /** A text field representing the resulting BHT index of the branch instruction. */
    lateinit var indexField: JTextField
        private set

    /** A combo box for selecting the number of BHT entries. */
    lateinit var bhtEntriesBox: JComboBox<Int>
        private set

    /** A combo box for selecting the history size. */
    lateinit var historySizeBox: JComboBox<Int>
        private set

    /** A combo box for selecting the initial value. */
    lateinit var initialBHTSizeBox: JComboBox<String>
        private set

    /** The table representing the BHT. */
    var bhtTable: JTable
        private set

    /** The text field for the log output. */
    lateinit var logArea: JTextArea
        private set

    init {
        val layout = BorderLayout()
        layout.vgap = 10
        layout.hgap = 10
        setLayout(layout)

        bhtTable = createAndInitTable()

        add(buildConfigPanel(), BorderLayout.NORTH)
        add(buildInfoPanel(), BorderLayout.WEST)
        add(JScrollPane(bhtTable), BorderLayout.CENTER)
        add(buildLogPanel(), BorderLayout.SOUTH)
    }

    /** Create the JTable representing the BHT. */
    private fun createAndInitTable(): JTable {
        val theTable = JTable()

        val doubleRenderer = object : DefaultTableCellRenderer() {
            private val formatter = DecimalFormat("##0.00")

            override fun setValue(value: Any?) {
                text = value?.let { formatter.format(it) } ?: ""
            }
        }
        doubleRenderer.horizontalAlignment = SwingConstants.CENTER

        val defRenderer = DefaultTableCellRenderer()
        defRenderer.horizontalAlignment = SwingConstants.CENTER

        theTable.setDefaultRenderer(Double::class.java, doubleRenderer)
        theTable.setDefaultRenderer(Int::class.java, defRenderer)
        theTable.setDefaultRenderer(String::class.java, defRenderer)

        theTable.selectionBackground = COLOR_PRE_PREDICTION
        theTable.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION)

        return theTable
    }

    /** Creates the panel holding the instruction, address, and index text fields. */
    private fun buildInfoPanel(): JPanel {
        instructionField = JTextField()
        addressField = JTextField()
        indexField = JTextField()

        listOf(instructionField, addressField, indexField).forEach {
            it.apply {
                columns = 10
                isEditable = false
                horizontalAlignment = JTextField.CENTER
            }
        }

        val panel = JPanel()
        val outerPanel = JPanel()
        outerPanel.layout = BorderLayout()

        val gbl = GridBagLayout()
        panel.layout = gbl

        val c = GridBagConstraints()

        c.insets = Insets(5, 5, 2, 5)
        c.gridx = 1
        c.gridy = 1

        panel.add(JLabel("Instruction"), c)
        c.gridy++
        panel.add(instructionField, c)
        c.gridy++
        panel.add(JLabel("@ Address"), c)
        c.gridy++
        panel.add(addressField, c)
        c.gridy++
        panel.add(JLabel("-> Index"), c)
        c.gridy++
        panel.add(indexField, c)

        outerPanel.add(panel, BorderLayout.NORTH)
        return outerPanel
    }

    /**
     * Creates the panel for configuring the tool.
     * Contains two combo boxes for selecting the number of BHT entries and history size.
     */
    private fun buildConfigPanel(): JPanel {
        val panel = JPanel()

        val sizes = vectorOf(8, 16, 32)
        val bits = vectorOf(1, 2)
        val initVals = vectorOf(BHT_DO_NOT_TAKE_BRANCH, BHT_TAKE_BRANCH)

        bhtEntriesBox = JComboBox(sizes)
        historySizeBox = JComboBox(bits)
        initialBHTSizeBox = JComboBox(initVals)

        panel.add(JLabel("# of BHT Entries"))
        panel.add(bhtEntriesBox)
        panel.add(JLabel("BHT history size"))
        panel.add(historySizeBox)
        panel.add(JLabel("Initial value"))
        panel.add(initialBHTSizeBox)

        return panel
    }

    /** Create the panel containing the log text area. */
    private fun buildLogPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BorderLayout()
        logArea = JTextArea()
        logArea.rows = 6
        logArea.isEditable = false

        panel.add(JLabel("Log"), BorderLayout.NORTH)
        panel.add(JScrollPane(logArea), BorderLayout.CENTER)

        return panel
    }
}