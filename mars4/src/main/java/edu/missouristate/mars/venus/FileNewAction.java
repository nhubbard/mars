package edu.missouristate.mars.venus;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action  for the File -> New menu item
 */
public class FileNewAction extends GuiAction {

    public FileNewAction(String name, Icon icon, String descrip,
                         Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    /**
     * Code to create a new document.  It clears the source code window.
     *
     * @param e component triggering this call
     */
    public void actionPerformed(ActionEvent e) {
        mainUI.editor.newFile();
    }
}
	
