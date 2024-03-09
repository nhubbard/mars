package edu.missouristate.mars.venus;

import org.jetbrains.annotations.NotNull;

import java.awt.event.*;
import javax.swing.*;

// Experimental version 3 August 2006 Pete Sanderson
// This will display the Settings popup menu upon right-click.
// Menu selections themselves are handled separately.
// Code below is adapted from Java Tutorial on working with menus.

public class PopupListener extends MouseAdapter {
    private final JPopupMenu popup;

    public PopupListener(JPopupMenu p) {
        popup = p;
    }

    public void mousePressed(@NotNull MouseEvent e) {
        maybeShowPopup(e);
    }

    public void mouseReleased(@NotNull MouseEvent e) {
        maybeShowPopup(e);
    }

    private void maybeShowPopup(@NotNull MouseEvent e) {
        if (e.isPopupTrigger()) {
            popup.show(e.getComponent(), e.getX(), e.getY());
        }
    }
}