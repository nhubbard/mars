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
 * program arguments can be entered and used.  If so, a text field
 * will be displayed where they can be interactively entered.
 */
public class SettingsProgramArgumentsAction extends GuiAction {


    public SettingsProgramArgumentsAction(String name, Icon icon, String descrip,
                                          Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        boolean selected = ((JCheckBoxMenuItem) e.getSource()).isSelected();
        Globals.getSettings().setProgramArguments(selected);
        if (selected) {
            Globals.getGui().getMainPane().getExecutePane().getTextSegmentWindow().addProgramArgumentsPanel();
        } else {
            Globals.getGui().getMainPane().getExecutePane().getTextSegmentWindow().removeProgramArgumentsPanel();
        }
    }

}