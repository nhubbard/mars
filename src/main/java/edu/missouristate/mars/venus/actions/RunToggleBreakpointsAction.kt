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

import edu.missouristate.mars.Globals.gui
import edu.missouristate.mars.venus.VenusUI
import java.awt.event.ActionEvent
import javax.swing.Icon
import javax.swing.KeyStroke

/**
 * Action class for the Run menu item to clear execution breakpoints that have been set.
 * It is a listener and is notified whenever a breakpoint is added or removed, thus will
 * set its enabled status true or false depending on whether breakpoints remain after that action.
 */
class RunToggleBreakpointsAction
/**
 * Create the object and register with text segment window as a listener on its table model.
 * The table model has not been created yet, so text segment window will hang onto this
 * registration info and transfer it to the table model upon creation (which happens with
 * each successful assembly).
 */
    (
    name: String?, icon: Icon?, descrip: String?,
    mnemonic: Int?, accel: KeyStroke?, gui: VenusUI?
) : GuiAction(name, icon, descrip, mnemonic, accel, gui) {
    /**
     * When this option is selected, tell text segment window to clear breakpoints in its table model.
     */
    override fun actionPerformed(e: ActionEvent) {
        gui!!.mainPane.executePane.textSegmentWindow.toggleBreakpoints()
    }
}