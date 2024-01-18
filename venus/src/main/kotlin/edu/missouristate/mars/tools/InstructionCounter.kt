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

@file:Suppress("DEPRECATION")

package edu.missouristate.mars.tools

import edu.missouristate.mars.mips.hardware.AccessNotice
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.*
import javax.swing.*

class InstructionCounter(
    title: String = "$NAME, $VERSION",
    heading: String = HEADING
) : AbstractMarsToolAndApplication(title, heading) {
    companion object {
        private const val NAME = "Instruction Counter"
        private const val VERSION = "Version 1.0 (Felipe Lessa)"
        private const val HEADING = "Counts the number of instructions executed"
    }

    private var allCounter = 0
    private lateinit var allCounterField: JTextField

    private var rFormatCounter = 0
    private lateinit var rFormatField: JTextField
    private lateinit var rFormatBar: JProgressBar

    private var iFormatCounter = 0
    private lateinit var iFormatField: JTextField
    private lateinit var iFormatBar: JProgressBar

    private var jFormatCounter = 0
    private lateinit var jFormatField: JTextField
    private lateinit var jFormatBar: JProgressBar

    private var lastAddress = -1

    override val toolName: String = NAME

    override fun buildMainDisplayArea(): JComponent {
        val panel = JPanel(GridBagLayout())

        // Initialize components

        allCounterField = JTextField("0", 10)
        allCounterField.isEditable = false

        rFormatField = JTextField("0", 10)
        rFormatField.isEditable = false
        rFormatBar = JProgressBar(JProgressBar.HORIZONTAL)
        rFormatBar.isStringPainted = true

        iFormatField = JTextField("0", 10)
        iFormatField.isEditable = false
        iFormatBar = JProgressBar(JProgressBar.HORIZONTAL)
        iFormatBar.isStringPainted = true

        jFormatField = JTextField("0", 10)
        jFormatField.isEditable = false
        jFormatBar = JProgressBar(JProgressBar.HORIZONTAL)
        jFormatBar.isStringPainted = true

        // Fields
        val c = GridBagConstraints()
        c.anchor = GridBagConstraints.LINE_START
        c.gridwidth = 1
        c.gridheight = 1
        c.gridx = 3
        c.gridy = 1
        c.insets = Insets(0, 0, 17, 0)
        panel.add(allCounterField, c)

        c.insets = Insets(0, 0, 0, 0)
        c.gridy++
        panel.add(rFormatField, c)

        c.gridy++
        panel.add(iFormatField, c)

        c.gridy++
        panel.add(jFormatField, c)

        // Labels
        c.anchor = GridBagConstraints.LINE_END
        c.gridx = 1
        c.gridwidth = 2
        c.gridy = 1
        c.insets = Insets(0, 0, 17, 0)
        panel.add(JLabel("Total instructions: "), c)

        c.insets = Insets(0, 0, 0, 0)
        c.gridx = 2
        c.gridwidth = 1
        c.gridy++
        panel.add(JLabel("R-type: "), c)

        c.gridy++
        panel.add(JLabel("I-type: "), c)

        c.gridy++
        panel.add(JLabel("J-type: "), c)

        // Progress bars
        c.insets = Insets(3, 3, 3, 3)
        c.gridx = 4
        c.gridy = 2
        panel.add(rFormatBar, c)

        c.gridy++
        panel.add(iFormatBar, c)

        c.gridy++
        panel.add(jFormatBar, c)

        return panel
    }

    override fun addAsObserver() {
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress)
    }

    override fun processMipsUpdate(resource: Observable, notice: AccessNotice) {
        if (!notice.accessIsFromMIPS) return
        if (notice.accessType != AccessNotice.AccessType.READ) return
        if (notice !is MemoryAccessNotice) return
        val a = notice.address
        if (a == lastAddress) return
        lastAddress = a
        allCounter++
        try {
            val statement = Memory.instance.getStatement(a)
            val instruction = statement?.getInstruction() as? BasicInstruction ?: return
            val format = instruction.instructionFormat
            when (format) {
                BasicInstructionFormat.R_FORMAT -> rFormatCounter++
                BasicInstructionFormat.I_FORMAT, BasicInstructionFormat.I_BRANCH_FORMAT -> iFormatCounter++
                BasicInstructionFormat.J_FORMAT -> jFormatCounter++
            }
        } catch (e: AddressErrorException) {
            e.printStackTrace()
        }
        updateDisplay()
    }

    override fun initializePreGUI() {
        allCounter = 0
        rFormatCounter = 0
        iFormatCounter = 0
        jFormatCounter = 0
        lastAddress = -1
    }

    override fun reset() {
        initializePreGUI()
        updateDisplay()
    }

    override fun updateDisplay() {
        allCounterField.text = allCounter.toString()

        rFormatField.text = rFormatCounter.toString()
        rFormatBar.maximum = allCounter
        rFormatBar.value = rFormatCounter

        iFormatField.text = iFormatCounter.toString()
        iFormatBar.maximum = allCounter
        iFormatBar.value = iFormatCounter

        jFormatField.text = jFormatCounter.toString()
        jFormatBar.maximum = allCounter
        jFormatBar.value = jFormatCounter

        if (allCounter == 0) {
            rFormatBar.string = "0%"
            iFormatBar.string = "0%"
            jFormatBar.string = "0%"
        } else {
            rFormatBar.string = "${((rFormatCounter * 100.0) / allCounter)}%"
            iFormatBar.string = "${((iFormatCounter * 100.0) / allCounter)}%"
            jFormatBar.string = "${((jFormatCounter * 100.0) / allCounter)}%"
        }
    }
}