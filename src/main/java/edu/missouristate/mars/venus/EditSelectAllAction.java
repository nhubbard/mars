package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/**
 * Action  for the Edit -> Copy menu item
 */
public class EditSelectAllAction extends GuiAction {

    public EditSelectAllAction(String name, Icon icon, String descrip,
                               Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        mainUI.getMainPane().getEditPane().selectAllText();
    }
}