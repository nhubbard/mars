package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action class for the Settings menu item to determine whether assemble operation applies
 * only to current file or to all files in its directory.
 */
public class SettingsAssembleAllAction extends GuiAction {


    public SettingsAssembleAllAction(String name, Icon icon, String descrip,
                                     Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        Globals.getSettings().setBooleanSetting(Settings.ASSEMBLE_ALL_ENABLED,
                ((JCheckBoxMenuItem) e.getSource()).isSelected());
    }

}