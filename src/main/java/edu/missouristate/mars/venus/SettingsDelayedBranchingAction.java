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

package edu.missouristate.mars.venus;

import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

import java.awt.event.*;
import javax.swing.*;

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
public class SettingsDelayedBranchingAction extends GuiAction {


    public SettingsDelayedBranchingAction(String name, Icon icon, String descrip,
                                          Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        Globals.getSettings().setBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED,
                ((JCheckBoxMenuItem) e.getSource()).isSelected());
        // 25 June 2007 Re-assemble if the situation demands it to maintain consistency.
        if (Globals.getGui() != null &&
                (FileStatus.get() == FileStatus.RUNNABLE ||
                        FileStatus.get() == FileStatus.RUNNING ||
                        FileStatus.get() == FileStatus.TERMINATED)
        ) {
            // Stop execution if executing -- should NEVER happen because this
            // Action's widget is disabled during MIPS execution.
            if (FileStatus.get() == FileStatus.RUNNING) {
                Simulator.getInstance().stopExecution(this);
            }
            Globals.getGui().getRunAssembleAction().actionPerformed(null);
        }
    }

}