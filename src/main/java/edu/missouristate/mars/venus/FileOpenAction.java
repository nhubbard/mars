package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;
import edu.missouristate.mars.util.*;

import java.util.ArrayList;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.io.*;
import java.beans.*;

/**
 * Action  for the File -> Open menu item
 */
public class FileOpenAction extends GuiAction {

    private File mostRecentlyOpenedFile;
    private JFileChooser fileChooser;
    private int fileFilterCount;
    private ArrayList fileFilterList;
    private PropertyChangeListener listenForUserAddedFileFilter;

    public FileOpenAction(String name, Icon icon, String descrip,
                          Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    /**
     * Launch a file chooser for name of file to open
     *
     * @param e component triggering this call
     */
    public void actionPerformed(ActionEvent e) {
        mainUI.editor.open();
    }

}
