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
package edu.missouristate.mars.venus.actions

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.Coprocessor0
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.hardware.Memory.Companion.dataBaseAddress
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.util.SystemIO.resetFiles
import edu.missouristate.mars.venus.FileStatus
import edu.missouristate.mars.venus.FileStatus.Companion.status
import edu.missouristate.mars.venus.VenusUI
import edu.missouristate.mars.venus.VenusUI.Companion.reset
import edu.missouristate.mars.venus.VenusUI.Companion.started
import java.awt.event.ActionEvent
import javax.swing.Icon
import javax.swing.KeyStroke

/**
 * Action  for the Run -> Reset menu item
 */
class RunResetAction(
    name: String?, icon: Icon?, descrip: String?,
    mnemonic: Int?, accel: KeyStroke?, gui: VenusUI?
) : GuiAction(name, icon, descrip, mnemonic, accel, gui) {
    /**
     * reset GUI components and MIPS resources
     */
    override fun actionPerformed(e: ActionEvent) {
        RunGoAction.resetMaxSteps()
        val name = this.getValue(NAME).toString()
        val executePane = mainUI.mainPane.executePane
        // The difficult part here is resetting the data segment.  Two approaches are:
        // 1. After each assembly, get a deep copy of the Globals.memory array
        //    containing data segment.  Then replace it upon reset.
        // 2. Simply re-assemble the program upon reset, and the assembler will
        //    build a new data segment.  Reset can only be done after a successful
        //    assembly, so there is "no" chance of assembler error.
        // I am choosing the second approach although it will slow down the reset
        // operation.  The first approach requires additional Memory class methods.
        try {
            Globals.program.assemble(
                RunAssembleAction.getMIPSProgramsToAssemble(),
                RunAssembleAction.getExtendedAssemblerEnabled(),
                RunAssembleAction.getWarningsAreErrors()
            )
        } catch (pe: ProcessingException) {
            mainUI.messagesPane.postMarsMessage( //pe.errors().generateErrorReport());
                "Unable to reset.  Please close file then re-open and re-assemble.\n"
            )
            return
        }
        RegisterFile.resetRegisters()
        Coprocessor1.resetRegisters()
        Coprocessor0.resetRegisters()

        executePane.registersWindow.clearHighlighting()
        executePane.registersWindow.updateRegisters()
        executePane.coprocessor1Window.clearHighlighting()
        executePane.coprocessor1Window.updateRegisters()
        executePane.coprocessor0Window.clearHighlighting()
        executePane.coprocessor0Window.updateRegisters()
        executePane.dataSegmentWindow.highlightCellForAddress(dataBaseAddress)
        executePane.dataSegmentWindow.clearHighlighting()
        executePane.textSegmentWindow.resetModifiedSourceCode()
        executePane.textSegmentWindow.codeHighlighting = true
        executePane.textSegmentWindow.highlightStepAtPC()
        mainUI.registersPane.selectedComponent = executePane.registersWindow
        status = FileStatus.StatusType.RUNNABLE
        reset = true
        started = false

        // Aug. 24, 2005 Ken Vollmar
        resetFiles() // Ensure that I/O "file descriptors" are initialized for a new program run

        mainUI.messagesPane.postRunMessage(
            "\n$name: reset completed.\n\n"
        )
    }
}