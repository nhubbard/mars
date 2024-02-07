package edu.missouristate.mars.venus;

import edu.missouristate.mars.Globals;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;

/**
 * Creates the tabbed areas in the UI and also created the internal windows that
 * exist in them.
 *
 * @author Sanderson and Bumgarner
 **/

public class MainPane extends JTabbedPane {
    EditPane editTab;
    final ExecutePane executeTab;
    final EditTabbedPane editTabbedPane;

    /**
     * Constructor for the MainPane class.
     **/

    public MainPane(VenusUI appFrame, Editor editor, RegistersWindow regs,
                    Coprocessor1Window cop1Regs, Coprocessor0Window cop0Regs) {
        super();
        this.setTabPlacement(JTabbedPane.TOP); //LEFT);
        this.getUI();
        editTabbedPane = new EditTabbedPane(appFrame, editor, this);
        executeTab = new ExecutePane(appFrame, regs, cop1Regs, cop0Regs);
        String editTabTitle = "Edit";
        String executeTabTitle = "Execute";

        this.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        this.addTab(editTabTitle, null, editTabbedPane);
        this.addTab(executeTabTitle, null, executeTab);

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