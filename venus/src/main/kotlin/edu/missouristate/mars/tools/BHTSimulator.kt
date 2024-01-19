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

@file:Suppress("DEPRECATION")

package edu.missouristate.mars.tools

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AccessNotice
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.RegisterFile
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.util.*
import javax.swing.JPanel

class BHTSimulator : AbstractMarsToolAndApplication(
    "${BHT_NAME}, $BHT_VERSION",
    BHT_HEADER
), ActionListener {
    companion object {
        const val BHT_DEFAULT_SIZE = 16
        const val BHT_DEFAULT_HISTORY = 1
        const val BHT_DEFAULT_INIT_VAL = false
        const val BHT_NAME = "BHT Simulator"
        const val BHT_VERSION = "Version 1.0 (Ingo Kofler)"
        const val BHT_HEADER = "Branch History Table Simulator"

        /**
         * Determines if the instruction is a branch instruction or not.
         *
         * @return `true` if the statement is a branch instruction
         */
        @JvmStatic
        private fun ProgramStatement.isBranchInstruction(): Boolean {
            val opCode = getBinaryStatement() ushr (32 - 6)
            val funct = getBinaryStatement() and 0x1F
            if (opCode == 0x01) {
                if (funct <= 0x07) return true // bltz, bgez, bltzl, bgezl
                if (funct in 0x10..0x13) return true // bltzal, bgezal, bltzall, bgczall
            }
            if (opCode in 0x04..0x07) return true // beq, bne, blez, bgtz
            return opCode in 0x14..0x17 // begl, bnel, blezl, bgtzl
        }

        /**
         * Checks if the branch instruction branches or not.
         */
        @JvmStatic
        private fun ProgramStatement.willBranch(): Boolean {
            val binaryStatement = getBinaryStatement()
            val opCode = binaryStatement ushr (32 - 6)
            val funct = binaryStatement and 0x1F
            val rs = binaryStatement ushr (32 - 6 - 5) and 0x1F
            val rt = binaryStatement ushr (32 - 6 - 5 - 5) and 0x1F

            val rsVal = RegisterFile.registers[rs].getValue()
            val rtVal = RegisterFile.registers[rt].getValue()

            if (opCode == 0x01) {
                when (funct) {
                    0x00, 0x02 -> return rsVal < 0  // bltz, bltzl
                    0x01, 0x03 -> return rsVal >= 0 // bgez, bgezl
                }
            }

            return when (opCode) {
                0x04, 0x14 -> rsVal == rtVal
                0x05, 0x15 -> rsVal != rtVal
                0x06, 0x16 -> rsVal <= 0
                0x07, 0x17 -> rsVal >= 0
                else -> true
            }
        }

        /**
         * Extract the target address of the branch.
         */
        @JvmStatic
        private fun ProgramStatement.extractBranchAddress(): Int {
            val offset = getBinaryStatement() and 0xFFFF
            return getAddress() + (offset shl 2).toShort() + 4
        }
    }

    private lateinit var gui: BHTSimGUI
    private lateinit var model: BHTableModel
    private var pendingBranchInstAddress: Int = 0
    private var lastBranchTaken: Boolean = false

    /**
     * Add BHTSimulator as an observer of the text segment.
     */
    override fun addAsObserver() {
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress)
        addAsObserver(RegisterFile.programCounter)
    }

    /**
     * Creates a GUI and initialize the GUI with the default values.
     */
    override fun buildMainDisplayArea(): JPanel {
        gui = BHTSimGUI()
        model = BHTableModel(BHT_DEFAULT_SIZE, BHT_DEFAULT_HISTORY, BHT_DEFAULT_INIT_VAL)

        with(gui) {
            bhtTable.model = model
            historySizeBox.selectedItem = BHT_DEFAULT_HISTORY
            bhtEntriesBox.selectedItem = BHT_DEFAULT_SIZE

            bhtEntriesBox.addActionListener(this@BHTSimulator)
            historySizeBox.addActionListener(this@BHTSimulator)
            initialBHTSizeBox.addActionListener(this@BHTSimulator)
        }

        return gui
    }

    /**
     * Returns the name of the tool.
     *
     * @return the tool's name as String
     */
    override val toolName: String = BHT_NAME

    /**
     * Performs a reset of the simulator.
     * This causes the BHT to be reset and the log messages to be cleared.
     */
    override fun reset() {
        resetSimulator()
    }

    /**
     * Handles the actions when selecting another value in one of the two combo boxes.
     * Selecting a different BHT size or history causes a reset of the simulator.
     */
    override fun actionPerformed(e: ActionEvent) {
        if (e.source == gui.bhtEntriesBox || e.source == gui.historySizeBox || e.source == gui.initialBHTSizeBox)
            resetSimulator()
    }

    /**
     * Resets the simulator by clearing the GUI elements and resetting the BHT.
     */
    private fun resetSimulator() {
        with(gui) {
            instructionField.text = ""
            addressField.text = ""
            indexField.text = ""
            logArea.text = ""
            model.initBHT(
                bhtEntriesBox.selectedItem as Int,
                historySizeBox.selectedItem as Int,
                initialBHTSizeBox.selectedItem == BHTSimGUI.BHT_TAKE_BRANCH
            )
            pendingBranchInstAddress = 0
            lastBranchTaken = false
        }
    }

    /**
     * Handles the execution branch instruction.
     * This method is called each time a branch instruction is executed.
     * Based on the address of the instruction, the corresponding index into the BHT is calculated.
     * The prediction is obtained from the BHT at the calculated index and is visualized appropriately.
     *
     * @param statement the branch statement that is executed
     */
    private fun handlePreBranchInstruction(statement: ProgramStatement) {
        val statementString = statement.getBasicAssemblyStatement()
        val address = statement.getAddress()
        val index = model.getIndexForAddress(address)

        with(gui) {
            instructionField.text = statementString
            addressField.text = "0x${address.toHexString()}"
            indexField.text = index.toString()
            bhtTable.selectionBackground = BHTSimGUI.COLOR_PRE_PREDICTION
            bhtTable.addRowSelectionInterval(index, index)
            logArea.let {
                it.append("Instruction $statementString at address 0x${address.toHexString()} maps to index $index\n")
                it.append("Branches to address 0x${statement.extractBranchAddress()}\n")
                it.append("Prediction is ${if (model.getPredictionAt(index)) BHTSimGUI.BHT_TAKE_BRANCH else BHTSimGUI.BHT_DO_NOT_TAKE_BRANCH}\n")
                it.caretPosition = it.document.length
            }
        }
    }

    /**
     * Handles the execution of the branch instruction.
     * The correctness of the prediction is visualized in both the table and the log message area.
     * The BHT is updated based on the information if the branch instruction was taken or not.
     *
     * @param address     the address of the branch instruction
     * @param branchTaken the information if the branch is taken or not (determined in a step before)
     */
    private fun handleBranchExecution(address: Int, branchTaken: Boolean) {
        val index = model.getIndexForAddress(address)
        val correctPrediction = model.getPredictionAt(index) == branchTaken
        gui.bhtTable.selectionBackground = if (correctPrediction) BHTSimGUI.COLOR_PREDICTION_CORRECT else BHTSimGUI.COLOR_PREDICTION_INCORRECT
        gui.logArea.append("Branch ${if (branchTaken) "taken" else "not taken"}, prediction was ${if (correctPrediction) "correct" else "incorrect"}\n\n")
        gui.logArea.caretPosition = gui.logArea.document.length
        model.updatePredictionAt(index, branchTaken)
    }

    /**
     * Callback for text segment access by the MIPS simulator.
     * <p>
     * The method is called each time the text segment is accessed to fetch the next instruction.
     * If the next instruction to execute was a branch instruction, the branch prediction is performed and visualized.
     * In case the last instruction was a branch instruction, the outcome of the branch prediction is analyzed and visualized.
     *
     * @param resource the observed resource
     * @param notice   signals the type of access (memory, register etc.)
     */
    override fun processMipsUpdate(resource: Observable, notice: AccessNotice) {
        if (!notice.accessIsFromMIPS) return
        if (notice.accessType == AccessNotice.AccessType.READ && notice is MemoryAccessNotice) {
            // Access the statement in the text segment without notifying other tools
            val statement = Memory.instance.getStatement(notice.address, false)
            // Handle possible null pointers at the end of the program
            if (statement != null) {
                var clearTextFields = true
                // Check if there's a pending branch to handle
                if (pendingBranchInstAddress != 0) {
                    handleBranchExecution(pendingBranchInstAddress, lastBranchTaken)
                    clearTextFields = false
                    pendingBranchInstAddress = 0
                }
                // If the current instruction is a branch instruction...
                if (statement.isBranchInstruction()) {
                    handlePreBranchInstruction(statement)
                    lastBranchTaken = statement.willBranch()
                    pendingBranchInstAddress = statement.getAddress()
                    clearTextFields = false
                }
                // Clear text fields and selection
                if (clearTextFields) {
                    gui.instructionField.text = ""
                    gui.addressField.text = ""
                    gui.indexField.text = ""
                    gui.bhtTable.clearSelection()
                }
            } else {
                // Check if there's a pending branch to handle
                if (pendingBranchInstAddress != 0) {
                    handleBranchExecution(pendingBranchInstAddress, lastBranchTaken)
                    pendingBranchInstAddress = 0
                }
            }
        }
    }
}