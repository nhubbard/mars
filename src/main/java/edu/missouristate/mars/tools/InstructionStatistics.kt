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

package edu.missouristate.mars.tools

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AccessNotice
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.*
import javax.swing.*

class InstructionStatistics(
    title: String = "$NAME, $VERSION",
    heading: String = HEADING
) : AbstractMarsToolAndApplication(title, heading) {
    companion object {
        private const val NAME = "Instruction Statistics"
        private const val VERSION = "Version 1.0 (Ingo Kofler)"
        private const val HEADING = NAME
    }

    private enum class InstructionCategory(val rawValue: Int) {
        ALU(0),
        JUMP(1),
        BRANCH(2),
        MEMORY(3),
        OTHER(4);

        companion object {
            @JvmStatic
            fun fromInt(rawValue: Int) =
                entries.firstOrNull { it.rawValue == rawValue } ?: OTHER
        }
    }

    private lateinit var totalCounterField: JTextField
    private lateinit var instructionCounterFields: Array<JTextField>
    private lateinit var instructionProgressBars: Array<JProgressBar>
    private var totalCounter: Int = 0
    private val counters = IntArray(InstructionCategory.entries.size)
    private val categoryLabels = arrayOf("ALU", "Jump", "Branch", "Memory", "Other")

    /**
     * The last address we saw.
     * Ignored because the only way for a program to execute twice is to enter an infinite loop.
     * Such a condition is not the concern for this tool.
     */
    private var lastAddress = -1

    override val toolName: String = NAME

    override fun buildMainDisplayArea(): JComponent {
        val panel = JPanel(GridBagLayout())

        totalCounterField = JTextField("0", 10)
        totalCounterField.isEditable = false

        instructionCounterFields = Array(InstructionCategory.entries.size) {
            JTextField("0", 10).apply {
                isEditable = false
            }
        }
        instructionProgressBars = Array(InstructionCategory.entries.size) {
            JProgressBar(JProgressBar.HORIZONTAL).apply {
                isStringPainted = true
            }
        }

        val c = GridBagConstraints()
        c.anchor = GridBagConstraints.LINE_START
        c.gridheight = 1
        c.gridwidth = 1

        // Place the label and text field for the total instructions counter
        c.gridx = 2
        c.gridy = 1
        c.insets = Insets(0, 0, 17, 0)
        panel.add(JLabel("Total: "), c)
        c.gridx = 3
        panel.add(totalCounterField, c)

        c.insets = Insets(3, 3,3, 3)

        // Place the label, text field, and progress bars for each category
        for (i in 0..<InstructionCategory.entries.size) {
            c.gridy++
            c.gridx = 2
            panel.add(JLabel("${categoryLabels[i]}: "), c)
            c.gridx = 3
            panel.add(instructionCounterFields[i], c)
            c.gridx = 4
            panel.add(instructionProgressBars[i], c)
        }

        return panel
    }

    override fun addAsObserver() {
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress)
    }

    private fun getInstructionCategory(statement: ProgramStatement): InstructionCategory {
        val opCode = statement.getBinaryStatement() ushr (32 - 6)
        val funct = statement.getBinaryStatement() and 0x1F

        return when {
            opCode == 0x00 -> when {
                funct == 0x00 -> InstructionCategory.ALU
                funct in 0x02..0x07 -> InstructionCategory.ALU
                funct == 0x08 || funct == 0x09 -> InstructionCategory.JUMP
                0x10 <= funct -> InstructionCategory.ALU
                else -> InstructionCategory.OTHER
            }
            opCode == 0x01 -> when {
                funct <= 0x07 -> InstructionCategory.BRANCH
                funct in 0x10..0x13 -> InstructionCategory.BRANCH
                else -> InstructionCategory.OTHER
            }
            opCode == 0x02 || opCode == 0x03 -> InstructionCategory.JUMP
            opCode <= 0x07 -> InstructionCategory.BRANCH
            opCode <= 0x0F -> InstructionCategory.ALU
            opCode in 0x14..0x17 -> InstructionCategory.BRANCH
            opCode in 0x20..0x26 -> InstructionCategory.MEMORY
            opCode in 0x28..0x2E -> InstructionCategory.MEMORY
            else -> InstructionCategory.OTHER
        }
    }

    override fun processMipsUpdate(resource: Observable, notice: AccessNotice) {
        if (
            !notice.accessIsFromMIPS ||
            notice.accessType != AccessNotice.AccessType.READ ||
            notice !is MemoryAccessNotice
        ) return
        val a = notice.address
        if (a == lastAddress) return
        lastAddress = a
        Memory.instance.getStatement(notice.address, false)?.let { statement ->
            val category = getInstructionCategory(statement)
            totalCounter++
            counters[category.rawValue]++
            updateDisplay()
        }
    }

    override fun initializePreGUI() {
        totalCounter = 0
        lastAddress = -1
        counters.fill(0)
    }

    override fun reset() {
        initializePreGUI()
        updateDisplay()
    }

    override fun updateDisplay() {
        totalCounterField.text = totalCounter.toString()
        for (i in 0..<InstructionCategory.entries.size) {
            instructionCounterFields[i].text = counters[i].toString()
            instructionProgressBars[i].maximum = totalCounter
            instructionProgressBars[i].value = counters[i]
        }
    }
}