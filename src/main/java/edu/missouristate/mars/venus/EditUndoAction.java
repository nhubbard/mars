package edu.missouristate.mars.venus;

import java.awt.event.*;
import javax.swing.*;

/**
 * Action  for the Edit -> Undo menu item
 */
public class EditUndoAction extends GuiAction {

    public EditUndoAction(String name, Icon icon, String descrip,
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
            editPane.undo();
            updateUndoState();
            mainUI.editRedoAction.updateRedoState();
        }
    }

    void updateUndoState() {
        EditPane editPane = mainUI.getMainPane().getEditPane();
        setEnabled(editPane != null && editPane.getUndoManager().canUndo());
        //new Throwable("update undo state: "+(editPane != null && editPane.getUndoManager().canUndo())).printStackTrace();
    }
}