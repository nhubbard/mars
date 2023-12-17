package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;

import java.awt.*;
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
        mainUI.setStarted(true);
        mainUI.messagesPane.setSelectedComponent(mainUI.messagesPane.runTab);
        executePane.getTextSegmentWindow().setCodeHighlighting(true);

        if (Globals.getSettings().getBackSteppingEnabled()) {
            boolean inDelaySlot = Globals.program.getBackStepper().inDelaySlot(); // Added 25 June 2007
            Memory.getInstance().addObserver(executePane.getDataSegmentWindow());
            RegisterFile.addRegistersObserver(executePane.getRegistersWindow());
            Coprocessor0.addRegistersObserver(executePane.getCoprocessor0Window());
            Coprocessor1.addRegistersObserver(executePane.getCoprocessor1Window());
            Globals.program.getBackStepper().backStep();
            Memory.getInstance().deleteObserver(executePane.getDataSegmentWindow());
            RegisterFile.deleteRegistersObserver(executePane.getRegistersWindow());
            executePane.getRegistersWindow().updateRegisters();
            executePane.getCoprocessor1Window().updateRegisters();
            executePane.getCoprocessor0Window().updateRegisters();
            executePane.getDataSegmentWindow().updateValues();
            executePane.getTextSegmentWindow().highlightStepAtPC(inDelaySlot); // Argument aded 25 June 2007
            FileStatus.set(FileStatus.RUNNABLE);
            // if we've backed all the way, disable the button
            //    if (Globals.program.getBackStepper().empty()) {
            //     ((AbstractAction)((AbstractButton)e.getSource()).getAction()).setEnabled(false);
            //}
         /*
         if (pe !=null) {
            RunGoAction.resetMaxSteps();
            mainUI.getMessagesPane().postMarsMessage(
                                pe.errors().generateErrorReport());
            mainUI.getMessagesPane().postMarsMessage(
                                "\n"+name+": execution terminated with errors.\n\n");
            mainUI.getRegistersPane().setSelectedComponent(executePane.getCoprocessor0Window());
            FileStatus.set(FileStatus.TERMINATED); // should be redundant.
         					executePane.getTextSegmentWindow().setCodeHighlighting(true);
         	executePane.getTextSegmentWindow().unhighlightAllSteps();
            executePane.getTextSegmentWindow().highlightStepAtAddress(RegisterFile.getProgramCounter()-4);
         }
         */
            mainUI.setReset(false);
        }
    }
}