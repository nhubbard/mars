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

package edu.missouristate.mars.venus.actions;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.venus.FileStatus;
import edu.missouristate.mars.venus.VenusUI;
import edu.missouristate.mars.venus.panes.ExecutePane;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action  for the Run -> Backstep menu item
 */
public class RunBackstepAction extends GuiAction {

    String name;
    ExecutePane executePane;

    public RunBackstepAction(String name, Icon icon, String descrip,
                             Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    /**
     * perform next simulated instruction step.
     */
    public void actionPerformed(ActionEvent e) {
        name = this.getValue(Action.NAME).toString();
        executePane = mainUI.getMainPane().getExecutePane();
        boolean done = false;
        if (!FileStatus.isAssembled()) {
            // note: this should never occur since backstepping is only enabled after successful assembly.
            JOptionPane.showMessageDialog(mainUI, "The program must be assembled before it can be run.");
            return;
        }
        VenusUI.setStarted(true);
        mainUI.getMessagesPane().setSelectedComponent(mainUI.getMessagesPane().runTab);
        executePane.getTextSegmentWindow().setCodeHighlighting(true);

        if (Globals.getSettings().getBackSteppingEnabled()) {
            boolean inDelaySlot = Globals.program.getBackStepper().inDelaySlot(); // Added 25 June 2007
            Memory.getInstance().addObserver(executePane.getDataSegmentWindow());
            RegisterFile.addRegisterObserver(executePane.getRegistersWindow());
            Coprocessor0.addRegisterObserver(executePane.getCoprocessor0Window());
            Coprocessor1.addRegisterObserver(executePane.getCoprocessor1Window());
            Globals.program.getBackStepper().backStep();
            Memory.getInstance().deleteObserver(executePane.getDataSegmentWindow());
            RegisterFile.deleteRegisterObserver(executePane.getRegistersWindow());
            executePane.getRegistersWindow().updateRegisters();
            executePane.getCoprocessor1Window().updateRegisters();
            executePane.getCoprocessor0Window().updateRegisters();
            executePane.getDataSegmentWindow().updateValues();
            executePane.getTextSegmentWindow().highlightStepAtPC(inDelaySlot); // Argument aded 25 June 2007
            FileStatus.set(FileStatus.RUNNABLE);
            // if we've backed all the way, disable the button
            VenusUI.setReset(false);
        }
    }
}