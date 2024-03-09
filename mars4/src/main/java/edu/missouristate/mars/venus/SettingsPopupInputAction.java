package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;
import org.jetbrains.annotations.NotNull;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action class for the Settings menu item to control use of popup dialog for input syscalls.
 */
public class SettingsPopupInputAction extends GuiAction {


    public SettingsPopupInputAction(String name, Icon icon, String descrip,
                                    Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(@NotNull ActionEvent e) {
        boolean usePopup = ((JCheckBoxMenuItem) e.getSource()).isSelected();
        Globals.getSettings().setBooleanSetting(Settings.POPUP_SYSCALL_INPUT, usePopup);
    }

}