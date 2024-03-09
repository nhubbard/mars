package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;
import org.jetbrains.annotations.NotNull;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action class for the Settings menu item to control automatic assemble of file upon opening.
 */
public class SettingsAssembleOnOpenAction extends GuiAction {


    public SettingsAssembleOnOpenAction(String name, Icon icon, String descrip,
                                        Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(@NotNull ActionEvent e) {
        Globals.getSettings().setBooleanSetting(Settings.ASSEMBLE_ON_OPEN_ENABLED,
                ((JCheckBoxMenuItem) e.getSource()).isSelected());
    }

}