package edu.missouristate.mars.venus;

import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import java.io.*;

/**
 * Action class for the Settings menu item to control delayed branching.
 * Note: Changing this setting while the current program is runnable
 * (assembled, or stepped execution) or terminated triggers a re-assembly.
 * This is necessary to maintain consistency because the machine
 * code assembled for branch instructions differs depending on
 * this setting -- would branch to incorrect address if setting
 * were changed between assembly and execution.
 * Note: This action is disabled while the MIPS program is running.
 * The user need only pause or stop execution to re-enable it.
 */
public class SettingsDelayedBranchingAction extends GuiAction {


    public SettingsDelayedBranchingAction(String name, Icon icon, String descrip,
                                          Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        Globals.getSettings().setBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED,
                ((JCheckBoxMenuItem) e.getSource()).isSelected());
        // 25 June 2007 Re-assemble if the situation demands it to maintain consistency.
        if (Globals.getGui() != null &&
                (FileStatus.get() == FileStatus.RUNNABLE ||
                        FileStatus.get() == FileStatus.RUNNING ||
                        FileStatus.get() == FileStatus.TERMINATED)
        ) {
            // Stop execution if executing -- should NEVER happen because this
            // Action's widget is disabled during MIPS execution.
            if (FileStatus.get() == FileStatus.RUNNING) {
                Simulator.getInstance().stopExecution(this);
            }
            Globals.getGui().getRunAssembleAction().actionPerformed(null);
        }
    }

}