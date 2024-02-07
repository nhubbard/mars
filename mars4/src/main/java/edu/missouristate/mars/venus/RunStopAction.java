package edu.missouristate.mars.venus;

import edu.missouristate.mars.simulator.*;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action class for the Run -> Stop menu item (and toolbar icon)
 */
public class RunStopAction extends GuiAction {


    public RunStopAction(String name, Icon icon, String descrip,
                         Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        Simulator.getInstance().stopExecution(this);
        // RunGoAction's "stopped" method will take care of the cleanup.
    }

}