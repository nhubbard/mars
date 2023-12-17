package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import java.io.*;

/**
 * Action  for the File -> Save As menu item
 */
public class FileSaveAsAction extends GuiAction {

    public FileSaveAsAction(String name, Icon icon, String descrip,
                            Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }


    public void actionPerformed(ActionEvent e) {
        mainUI.editor.saveAs();
    }
}