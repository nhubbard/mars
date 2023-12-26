package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;
import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action  for the Run -> Reset menu item
 */
public class RunResetAction extends GuiAction {

    public RunResetAction(String name, Icon icon, String descrip,
                          Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    /**
     * reset GUI components and MIPS resources
     */
    public void actionPerformed(ActionEvent e) {
        RunGoAction.resetMaxSteps();
        String name = this.getValue(Action.NAME).toString();
        ExecutePane executePane = mainUI.getMainPane().getExecutePane();
        // The difficult part here is resetting the data segment.  Two approaches are:
        // 1. After each assembly, get a deep copy of the Globals.memory array
        //    containing data segment.  Then replace it upon reset.
        // 2. Simply re-assemble the program upon reset, and the assembler will
        //    build a new data segment.  Reset can only be done after a successful
        //    assembly, so there is "no" chance of assembler error.
        // I am choosing the second approach although it will slow down the reset
        // operation.  The first approach requires additional Memory class methods.
        try {
            Globals.program.assemble(RunAssembleAction.getMIPSProgramsToAssemble(),
                    RunAssembleAction.getExtendedAssemblerEnabled(),
                    RunAssembleAction.getWarningsAreErrors());
        } catch (ProcessingException pe) {
            mainUI.getMessagesPane().postMarsMessage(
                    //pe.errors().generateErrorReport());
                    "Unable to reset.  Please close file then re-open and re-assemble.\n");
            return;
        }
        RegisterFile.resetRegisters();
        Coprocessor1.resetRegisters();
        Coprocessor0.resetRegisters();

        executePane.getRegistersWindow().clearHighlighting();
        executePane.getRegistersWindow().updateRegisters();
        executePane.getCoprocessor1Window().clearHighlighting();
        executePane.getCoprocessor1Window().updateRegisters();
        executePane.getCoprocessor0Window().clearHighlighting();
        executePane.getCoprocessor0Window().updateRegisters();
        executePane.getDataSegmentWindow().highlightCellForAddress(Memory.getDataBaseAddress());
        executePane.getDataSegmentWindow().clearHighlighting();
        executePane.getTextSegmentWindow().resetModifiedSourceCode();
        executePane.getTextSegmentWindow().setCodeHighlighting(true);
        executePane.getTextSegmentWindow().highlightStepAtPC();
        mainUI.getRegistersPane().setSelectedComponent(executePane.getRegistersWindow());
        FileStatus.set(FileStatus.RUNNABLE);
        VenusUI.setReset(true);
        VenusUI.setStarted(false);

        // Aug. 24, 2005 Ken Vollmar
        SystemIO.resetFiles();  // Ensure that I/O "file descriptors" are initialized for a new program run

        mainUI.getMessagesPane().postRunMessage(
                "\n" + name + ": reset completed.\n\n");
    }
}