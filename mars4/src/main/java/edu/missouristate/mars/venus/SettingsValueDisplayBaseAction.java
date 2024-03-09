package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;
import org.jetbrains.annotations.NotNull;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action class for the Settings menu item to control number base (10 or 16) of memory/register contents.
 */
public class SettingsValueDisplayBaseAction extends GuiAction {


    public SettingsValueDisplayBaseAction(String name, Icon icon, String descrip,
                                          Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(@NotNull ActionEvent e) {
        boolean isHex = ((JCheckBoxMenuItem) e.getSource()).isSelected();
        Globals.getGui().getMainPane().getExecutePane().getValueDisplayBaseChooser().setSelected(isHex);
        Globals.getSettings().setBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX, isHex);
    }

}