package edu.missouristate.mars.venus;

import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import java.io.*;

/**
 * Action class for the Settings menu item to control whether or not
 * assembler warnings are considered errors.  If so, a program generating
 * warnings but not errors will not assemble.
 */
public class SettingsStartAtMainAction extends GuiAction {


    public SettingsStartAtMainAction(String name, Icon icon, String descrip,
                                     Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        Globals.getSettings().setBooleanSetting(Settings.START_AT_MAIN, ((JCheckBoxMenuItem) e.getSource()).isSelected());
    }

}