package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action class for the Settings menu item to control use of extended (pseudo) instructions or formats.
 */
public class SettingsExtendedAction extends GuiAction {


    public SettingsExtendedAction(String name, Icon icon, String descrip,
                                  Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        Globals.getSettings().setBooleanSetting(Settings.EXTENDED_ASSEMBLER_ENABLED, ((JCheckBoxMenuItem) e.getSource()).isSelected());
    }

}