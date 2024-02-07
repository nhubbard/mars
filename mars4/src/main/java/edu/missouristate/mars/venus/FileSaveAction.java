package edu.missouristate.mars.venus;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action  for the File -> Save menu item
 */
public class FileSaveAction extends GuiAction {

    public FileSaveAction(String name, Icon icon, String descrip,
                          Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    /**
     * saves the file, if not alredy saved it will do a saveAs
     */

    public void actionPerformed(ActionEvent e) {
        mainUI.editor.save();
    }

}
