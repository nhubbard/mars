package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.util.*;
import org.jetbrains.annotations.Nullable;

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

                mainUI.messagesPane.postMarsMessage(
                        name + ": running " + FileStatus.getFile().getName() + "\n\n");
                mainUI.getMessagesPane().selectRunMessageTab();
                executePane.getTextSegmentWindow().setCodeHighlighting(false);
                executePane.getTextSegmentWindow().unhighlightAllSteps();
                //FileStatus.set(FileStatus.RUNNING);
                mainUI.setMenuState(FileStatus.RUNNING);
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

    public void paused(boolean done, int pauseReason, ProcessingException pe) {
        // I doubt this can happen (pause when execution finished), but if so treat it as stopped.
        if (done) {
            stopped(pe, Simulator.NORMAL_TERMINATION);
            return;
        }
        if (pauseReason == Simulator.BREAKPOINT) {
            mainUI.messagesPane.postMarsMessage(
                    name + ": execution paused at breakpoint: " + FileStatus.getFile().getName() + "\n\n");
        } else {
            mainUI.messagesPane.postMarsMessage(
                    name + ": execution paused by user: " + FileStatus.getFile().getName() + "\n\n");
        }
        mainUI.getMessagesPane().selectMarsMessageTab();
        executePane.getTextSegmentWindow().setCodeHighlighting(true);
        executePane.getTextSegmentWindow().highlightStepAtPC();
        executePane.getRegistersWindow().updateRegisters();
        executePane.getCoprocessor1Window().updateRegisters();
        executePane.getCoprocessor0Window().updateRegisters();
        executePane.getDataSegmentWindow().updateValues();
        FileStatus.set(FileStatus.RUNNABLE);
        VenusUI.setReset(false);
    }

    /**
     * Method to be called when Stop is selected through menu/toolbar/shortcut.  This should only
     * happen when MIPS program is running (FileStatus.RUNNING).  See VenusUI.java for enabled
     * status of menu items based on FileStatus.  Display finalized values as if execution
     * terminated due to completion or exception.
     */

    public void stopped(@Nullable ProcessingException pe, int reason) {
        // show final register and data segment values.
        executePane.getRegistersWindow().updateRegisters();
        executePane.getCoprocessor1Window().updateRegisters();
        executePane.getCoprocessor0Window().updateRegisters();
        executePane.getDataSegmentWindow().updateValues();
        FileStatus.set(FileStatus.TERMINATED);
        SystemIO.resetFiles(); // close any files opened in MIPS program
        // Bring coprocessor 0 to the front if terminated due to exception.
        if (pe != null) {
            mainUI.getRegistersPane().setSelectedComponent(executePane.getCoprocessor0Window());
            executePane.getTextSegmentWindow().setCodeHighlighting(true);
            executePane.getTextSegmentWindow().unhighlightAllSteps();
            executePane.getTextSegmentWindow().highlightStepAtAddress(RegisterFile.getProgramCounter() - 4);
        }
        switch (reason) {
            case Simulator.NORMAL_TERMINATION:
                mainUI.getMessagesPane().postMarsMessage(
                        "\n" + name + ": execution completed successfully.\n\n");
                mainUI.getMessagesPane().postRunMessage(
                        "\n-- program is finished running --\n\n");
                mainUI.getMessagesPane().selectRunMessageTab();
                break;
            case Simulator.CLIFF_TERMINATION:
                mainUI.getMessagesPane().postMarsMessage(
                        "\n" + name + ": execution terminated by null instruction.\n\n");
                mainUI.getMessagesPane().postRunMessage(
                        "\n-- program is finished running (dropped off bottom) --\n\n");
                mainUI.getMessagesPane().selectRunMessageTab();
                break;
            case Simulator.EXCEPTION:
                mainUI.getMessagesPane().postMarsMessage(
                        Objects.requireNonNull(pe).errors().generateErrorReport());
                mainUI.getMessagesPane().postMarsMessage(
                        "\n" + name + ": execution terminated with errors.\n\n");
                break;
            case Simulator.PAUSE_OR_STOP:
                mainUI.getMessagesPane().postMarsMessage(
                        "\n" + name + ": execution terminated by user.\n\n");
                mainUI.getMessagesPane().selectMarsMessageTab();
                break;
            case Simulator.MAX_STEPS:
                mainUI.getMessagesPane().postMarsMessage(
                        "\n" + name + ": execution step limit of " + maxSteps + " exceeded.\n\n");
                mainUI.getMessagesPane().selectMarsMessageTab();
                break;
            case Simulator.BREAKPOINT: // should never get here
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
                !Globals.getSettings().getBooleanSetting(Settings.PROGRAM_ARGUMENTS)) {
            return;
        }
        new ProgramArgumentList(programArguments).storeProgramArguments();
    }


}