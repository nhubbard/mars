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
import edu.missouristate.mars.Globals.settings
import edu.missouristate.mars.Settings
import edu.missouristate.mars.simulator.Simulator.Companion.getInstance
import edu.missouristate.mars.venus.FileStatus
import edu.missouristate.mars.venus.FileStatus.Companion.status
import edu.missouristate.mars.venus.VenusUI
import java.awt.event.ActionEvent
import javax.swing.Icon
import javax.swing.JCheckBoxMenuItem
import javax.swing.KeyStroke

/**
 * Action class for the Settings menu item to control delayed branching.
 * Note: Changing this setting while the current program is runnable
 * (assembled, or stepped execution) or terminated triggers a re-assembly.
 * This is necessary to maintain consistency because the machine
 * code assembled for branch instructions differs depending on
 * this setting -- would branch to incorrect address if setting
 * were changed between assembly and execution.
 * Note: This action is disabled while the MIPS program is running.
 * The user need only pause or stop execution to re-enable it.
 */
class SettingsDelayedBranchingAction(
    name: String?, icon: Icon?, descrip: String?,
    mnemonic: Int?, accel: KeyStroke?, gui: VenusUI?
) : GuiAction(name, icon, descrip, mnemonic, accel, gui) {
    override fun actionPerformed(e: ActionEvent) {
        settings.setBooleanSetting(
            Settings.DELAYED_BRANCHING_ENABLED,
            (e.source as JCheckBoxMenuItem).isSelected
        )
        // 25 June 2007 Re-assemble if the situation demands it to maintain consistency.
        if (gui != null &&
            (status == FileStatus.StatusType.RUNNABLE || status == FileStatus.StatusType.RUNNING || status == FileStatus.StatusType.TERMINATED)
        ) {
            // Stop execution if executing -- should NEVER happen because this
            // Action's widget is disabled during MIPS execution.
            if (status == FileStatus.StatusType.RUNNING) {
                getInstance().stopExecution(this)
            }
            gui!!.runAssembleAction.actionPerformed(null)
        }
    }
}