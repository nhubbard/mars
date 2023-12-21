package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action class for the Settings menu item to control whether
 * the running MIPS program can write to the text segment or
 * branch to the data segment.  This actions permit the program
 * to generate and execute binary code at runtime.  In other
 * words, modify itself.
 */
public class SettingsSelfModifyingCodeAction extends GuiAction {


    public SettingsSelfModifyingCodeAction(String name, Icon icon, String descrip,
                                           Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        Globals.getSettings().setBooleanSetting(Settings.ENABLE_SELF_MODIFYING_CODE,
                ((JCheckBoxMenuItem) e.getSource()).isSelected());
    }

}