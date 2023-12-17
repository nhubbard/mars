package edu.missouristate.mars.venus;

import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import java.io.*;

/**
 * Action class for the Settings menu item to control automatic assemble of file upon opening.
 */
public class SettingsAssembleOnOpenAction extends GuiAction {


    public SettingsAssembleOnOpenAction(String name, Icon icon, String descrip,
                                        Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        Globals.getSettings().setAssembleOnOpenEnabled(
                ((JCheckBoxMenuItem) e.getSource()).isSelected());
    }

}