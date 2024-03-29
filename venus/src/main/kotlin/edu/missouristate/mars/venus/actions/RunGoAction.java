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

package edu.missouristate.mars.venus.actions;

import edu.missouristate.mars.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.util.*;
import edu.missouristate.mars.venus.FileStatus;
import edu.missouristate.mars.venus.VenusUI;
import edu.missouristate.mars.venus.panes.ExecutePane;

import java.awt.event.*;
import java.util.Objects;
import javax.swing.*;

/**
 * Action class for the Run -> Go menu item (and toolbar icon)
 */
public class RunGoAction extends GuiAction {

    public static final int defaultMaxSteps = -1; // "forever", formerly 10000000; // 10 million
    public static int maxSteps = defaultMaxSteps;
    private String name;
    private ExecutePane executePane;

    public RunGoAction(String name, Icon icon, String descrip,
                       Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    /**
     * Action to take when GO is selected -- run the MIPS program!
     */
    public void actionPerformed(ActionEvent e) {
        name = this.getValue(Action.NAME).toString();
        executePane = mainUI.getMainPane().getExecutePane();
        if (FileStatus.isAssembled()) {
            if (!VenusUI.getStarted()) {
                processProgramArgumentsIfAny();  // DPS 17-July-2008
            }
            if (VenusUI.getReset() || VenusUI.getStarted()) {

                VenusUI.setStarted(true);  // added 8/27/05

                mainUI.getMessagesPane().postMarsMessage(
                        name + ": running " + FileStatus.getFile().getName() + "\n\n");
                mainUI.getMessagesPane().selectRunMessageTab();
                executePane.getTextSegmentWindow().setCodeHighlighting(false);
                executePane.getTextSegmentWindow().unhighlightAllSteps();
                //FileStatus.set(FileStatus.RUNNING);
                mainUI.setMenuState(FileStatus.StatusType.RUNNING);
                try {
                    int[] breakPoints = executePane.getTextSegmentWindow().getSortedBreakPointsArray();
                    boolean done = Globals.program.simulateFromPC(breakPoints, maxSteps, this);
                } catch (ProcessingException ignored) {
                }
            } else {
                // This should never occur because at termination the Go and Step buttons are disabled.
                JOptionPane.showMessageDialog(mainUI, "reset " + VenusUI.getReset() + " started " + VenusUI.getStarted());//"You must reset before you can execute the program again.");
            }
        } else {
            // note: this should never occur since "Go" is only enabled after successful assembly.
            JOptionPane.showMessageDialog(mainUI, "The program must be assembled before it can be run.");
        }
    }

    /**
     * Method to be called when Pause is selected through menu/toolbar/shortcut.  This should only
     * happen when MIPS program is running (FileStatus.RUNNING).  See VenusUI.java for enabled
     * status of menu items based on FileStatus.  Set GUI as if at breakpoint or executing
     * step by step.
     */

    public void paused(boolean done, Simulator.TerminationReason pauseReason, ProcessingException pe) {
        // I doubt this can happen (pause when execution finished), but if so treat it as stopped.
        if (done) {
            stopped(pe, Simulator.TerminationReason.NORMAL_TERMINATION);
            return;
        }
        if (pauseReason == Simulator.TerminationReason.BREAKPOINT) {
            mainUI.getMessagesPane().postMarsMessage(
                    name + ": execution paused at breakpoint: " + FileStatus.getFile().getName() + "\n\n");
        } else {
            mainUI.getMessagesPane().postMarsMessage(
                    name + ": execution paused by user: " + FileStatus.getFile().getName() + "\n\n");
        }
        mainUI.getMessagesPane().selectMarsMessageTab();
        executePane.getTextSegmentWindow().setCodeHighlighting(true);
        executePane.getTextSegmentWindow().highlightStepAtPC();
        executePane.getRegistersWindow().updateRegisters();
        executePane.getCoprocessor1Window().updateRegisters();
        executePane.getCoprocessor0Window().updateRegisters();
        executePane.getDataSegmentWindow().updateValues();
        FileStatus.Companion.setStatus(FileStatus.StatusType.RUNNABLE);
        VenusUI.setReset(false);
    }

    /**
     * Method to be called when Stop is selected through menu/toolbar/shortcut.  This should only
     * happen when MIPS program is running (FileStatus.RUNNING).  See VenusUI.java for enabled
     * status of menu items based on FileStatus.  Display finalized values as if execution
     * terminated due to completion or exception.
     */

    public void stopped(ProcessingException pe, Simulator.TerminationReason reason) {
        // show final register and data segment values.
        executePane.getRegistersWindow().updateRegisters();
        executePane.getCoprocessor1Window().updateRegisters();
        executePane.getCoprocessor0Window().updateRegisters();
        executePane.getDataSegmentWindow().updateValues();
        FileStatus.Companion.setStatus(FileStatus.StatusType.TERMINATED);
        SystemIO.resetFiles(); // close any files opened in MIPS program
        // Bring coprocessor 0 to the front if terminated due to exception.
        if (pe != null) {
            mainUI.getRegistersPane().setSelectedComponent(executePane.getCoprocessor0Window());
            executePane.getTextSegmentWindow().setCodeHighlighting(true);
            executePane.getTextSegmentWindow().unhighlightAllSteps();
            executePane.getTextSegmentWindow().highlightStepAtAddress(RegisterFile.getProgramCounter().getValue() - 4);
        }
        switch (reason) {
            case Simulator.TerminationReason.NORMAL_TERMINATION:
                mainUI.getMessagesPane().postMarsMessage(
                        "\n" + name + ": execution completed successfully.\n\n");
                mainUI.getMessagesPane().postRunMessage(
                        "\n-- program is finished running --\n\n");
                mainUI.getMessagesPane().selectRunMessageTab();
                break;
            case Simulator.TerminationReason.CLIFF_TERMINATION:
                mainUI.getMessagesPane().postMarsMessage(
                        "\n" + name + ": execution terminated by null instruction.\n\n");
                mainUI.getMessagesPane().postRunMessage(
                        "\n-- program is finished running (dropped off bottom) --\n\n");
                mainUI.getMessagesPane().selectRunMessageTab();
                break;
            case Simulator.TerminationReason.EXCEPTION:
                mainUI.getMessagesPane().postMarsMessage(
                        Objects.requireNonNull(pe).errors().generateReport());
                mainUI.getMessagesPane().postMarsMessage(
                        "\n" + name + ": execution terminated with errors.\n\n");
                break;
            case Simulator.TerminationReason.PAUSE_OR_STOP:
                mainUI.getMessagesPane().postMarsMessage(
                        "\n" + name + ": execution terminated by user.\n\n");
                mainUI.getMessagesPane().selectMarsMessageTab();
                break;
            case Simulator.TerminationReason.MAX_STEPS:
                mainUI.getMessagesPane().postMarsMessage(
                        "\n" + name + ": execution step limit of " + maxSteps + " exceeded.\n\n");
                mainUI.getMessagesPane().selectMarsMessageTab();
                break;
            case Simulator.TerminationReason.BREAKPOINT: // should never get here
                break;
        }
        RunGoAction.resetMaxSteps();
        VenusUI.setReset(false);
    }

    /**
     * Reset max steps limit to default value at termination of a simulated execution.
     */

    public static void resetMaxSteps() {
        maxSteps = defaultMaxSteps;
    }

    ////////////////////////////////////////////////////////////////////////////////////
    // Method to store any program arguments into MIPS memory and registers before
    // execution begins. Arguments go into the gap between $sp and kernel memory.
    // Argument pointers and count go into runtime stack and $sp is adjusted accordingly.
    // $a0 gets argument count (argc), $a1 gets stack address of first arg pointer (argv).
    private void processProgramArgumentsIfAny() {
        String programArguments = executePane.getTextSegmentWindow().getProgramArguments();
        if (programArguments == null || programArguments.isEmpty() ||
                !Globals.getSettings().getBooleanSetting(CoreSettings.ENABLE_PROGRAM_ARGUMENTS)) {
            return;
        }
        new ProgramArgumentList(programArguments).storeProgramArguments();
    }


}