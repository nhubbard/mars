package edu.missouristate.mars.venus;

import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import java.io.*;

/**
 * Action class for the Settings menu item to control number base (10 or 16) of memory addresses.
 */
public class SettingsAddressDisplayBaseAction extends GuiAction {


    public SettingsAddressDisplayBaseAction(String name, Icon icon, String descrip,
                                            Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        boolean isHex = ((JCheckBoxMenuItem) e.getSource()).isSelected();
        Globals.getGui().getMainPane().getExecutePane().getAddressDisplayBaseChooser().setSelected(isHex);
        Globals.getSettings().setDisplayAddressesInHex(isHex);
    }

}