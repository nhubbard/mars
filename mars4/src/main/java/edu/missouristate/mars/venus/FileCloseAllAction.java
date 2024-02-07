package edu.missouristate.mars.venus;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action  for the File -> Close All menu item
 */
public class FileCloseAllAction extends GuiAction {

    public FileCloseAllAction(String name, Icon icon, String descrip,
                              Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        mainUI.editor.closeAll();
    }
}