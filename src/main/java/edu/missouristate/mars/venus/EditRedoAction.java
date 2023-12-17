package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.undo.*;

/**
 * Action  for the Edit -> Redo menu item
 */
public class EditRedoAction extends GuiAction {

    public EditRedoAction(String name, Icon icon, String descrip,
                          Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
        setEnabled(false);
    }

    /**
     * Adapted from TextComponentDemo.java in the
     * Java Tutorial "Text Component Features"
     */
    public void actionPerformed(ActionEvent e) {
        EditPane editPane = mainUI.getMainPane().getEditPane();
        if (editPane != null) {
            editPane.redo();
            updateRedoState();
            mainUI.editUndoAction.updateUndoState();
        }
    }

    void updateRedoState() {
        EditPane editPane = mainUI.getMainPane().getEditPane();
        setEnabled(editPane != null && editPane.getUndoManager().canRedo());
    }
}
	
