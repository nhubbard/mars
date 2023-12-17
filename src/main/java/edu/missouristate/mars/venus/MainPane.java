package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;

import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.undo.*;
import java.text.*;
import java.util.*;
import java.io.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;

/**
 * Creates the tabbed areas in the UI and also created the internal windows that
 * exist in them.
 *
 * @author Sanderson and Bumgarner
 **/

public class MainPane extends JTabbedPane {
    EditPane editTab;
    ExecutePane executeTab;
    EditTabbedPane editTabbedPane;

    private VenusUI mainUI;

    /**
     * Constructor for the MainPane class.
     **/

    public MainPane(VenusUI appFrame, Editor editor, RegistersWindow regs,
                    Coprocessor1Window cop1Regs, Coprocessor0Window cop0Regs) {
        super();
        this.mainUI = appFrame;
        this.setTabPlacement(JTabbedPane.TOP); //LEFT);
        if (this.getUI() instanceof BasicTabbedPaneUI) {
            BasicTabbedPaneUI ui = (BasicTabbedPaneUI) this.getUI();
        }
        editTabbedPane = new EditTabbedPane(appFrame, editor, this);
        executeTab = new ExecutePane(appFrame, regs, cop1Regs, cop0Regs);
        String editTabTitle = "Edit"; //"<html><center>&nbsp;<br>E<br>d<br>i<br>t<br>&nbsp;</center></html>";
        String executeTabTitle = "Execute"; //"<html><center>&nbsp;<br>E<br>x<br>e<br>c<br>u<br>t<br>e<br>&nbsp;</center></html>";
        Icon editTabIcon = null;//new ImageIcon(Toolkit.getDefaultToolkit().getImage(this.getClass().getResource(Globals.imagesPath+"Edit_tab.jpg")));
        Icon executeTabIcon = null;//new ImageIcon(Toolkit.getDefaultToolkit().getImage(this.getClass().getResource(Globals.imagesPath+"Execute_tab.jpg")));

        this.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        this.addTab(editTabTitle, editTabIcon, editTabbedPane);

        // this.addTab("<html><center>&nbsp;<br>P<br>r<br>o<br>j<br>&nbsp;<br>1<br&nbsp;</center></html>", null, new JTabbedPane());
        // this.addTab("<html><center>&nbsp;<br>P<br>r<br>o<br>j<br>&nbsp;<br>2<br&nbsp;</center></html>", null, new JTabbedPane());
        // this.addTab("<html><center>&nbsp;<br>P<br>r<br>o<br>j<br>&nbsp;<br>3<br&nbsp;</center></html>", null, new JTabbedPane());
        // this.addTab("<html><center>&nbsp;<br>P<br>r<br>o<br>j<br>&nbsp;<br>4<br&nbsp;</center></html>", null, new JTabbedPane());

        this.addTab(executeTabTitle, executeTabIcon, executeTab);

        this.setToolTipTextAt(0, "Text editor for composing MIPS programs.");
        this.setToolTipTextAt(1, "View and control assembly language program execution.  Enabled upon successful assemble.");

        /* Listener has one specific purpose: when Execute tab is selected for the
         * first time, set the bounds of its internal frames by invoking the
         * setWindowsBounds() method.  Once this occurs, listener removes itself!
         * We do NOT want to reset bounds each time Execute tab is selected.
         * See ExecutePane.setWindowsBounds documentation for more details.
         */
        this.addChangeListener(
                new ChangeListener() {
                    public void stateChanged(ChangeEvent ce) {
                        JTabbedPane tabbedPane = (JTabbedPane) ce.getSource();
                        int index = tabbedPane.getSelectedIndex();
                        Component c = tabbedPane.getComponentAt(index);
                        ExecutePane executePane = Globals.getGui().getMainPane().getExecutePane();
                        if (c == executePane) {
                            executePane.setWindowBounds();
                            Globals.getGui().getMainPane().removeChangeListener(this);
                        }
                    }
                });
    }

    /**
     * Returns current edit pane.  Implementation changed for MARS 4.0 support
     * for multiple panes, but specification is same.
     *
     * @return the editor pane
     */
    public EditPane getEditPane() {
        return editTabbedPane.getCurrentEditTab();
    }

    /**
     * Returns component containing editor display
     *
     * @return the editor tabbed pane
     */
    public JComponent getEditTabbedPane() {
        return editTabbedPane;
    }

    /**
     * returns component containing execution-time display
     *
     * @return the execute pane
     */
    public ExecutePane getExecutePane() {
        return executeTab;
    }

    /**
     * returns component containing execution-time display.
     * Same as getExecutePane().
     *
     * @return the execute pane
     */
    public ExecutePane getExecuteTab() {
        return executeTab;
    }

}