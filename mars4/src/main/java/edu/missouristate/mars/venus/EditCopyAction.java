package edu.missouristate.mars.venus;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action  for the Edit -> Copy menu item
 */
public class EditCopyAction extends GuiAction {

    public EditCopyAction(String name, Icon icon, String descrip,
                          Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        mainUI.getMainPane().getEditPane().copyText();
    }
}