package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;

import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;

/**
 * Action class for the Run menu item to clear execution breakpoints that have been set.
 * It is a listener and is notified whenever a breakpoint is added or removed, thus will
 * set its enabled status true or false depending on whether breakpoints remain after that action.
 */
public class RunToggleBreakpointsAction extends GuiAction {

    /**
     * Create the object and register with text segment window as a listener on its table model.
     * The table model has not been created yet, so text segment window will hang onto this
     * registration info and transfer it to the table model upon creation (which happens with
     * each successful assembly).
     */
    public RunToggleBreakpointsAction(String name, Icon icon, String descrip,
                                      Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    /**
     * When this option is selected, tell text segment window to clear breakpoints in its table model.
     */
    public void actionPerformed(ActionEvent e) {
        Globals.getGui().getMainPane().getExecutePane().getTextSegmentWindow().toggleBreakpoints();
    }

}