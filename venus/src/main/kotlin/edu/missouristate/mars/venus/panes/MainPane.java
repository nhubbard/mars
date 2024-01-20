/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars.venus.panes;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.UIGlobals;
import edu.missouristate.mars.venus.Editor;
import edu.missouristate.mars.venus.windows.RegistersWindow;
import edu.missouristate.mars.venus.VenusUI;
import edu.missouristate.mars.venus.windows.Coprocessor0Window;
import edu.missouristate.mars.venus.windows.Coprocessor1Window;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;

/**
 * Creates the tabbed areas in the UI and also created the internal windows that
 * exist in them.
 *
 * @author Sanderson and Bumgarner
 */

public class MainPane extends JTabbedPane {
    EditPane editTab;
    final ExecutePane executeTab;
    final EditTabbedPane editTabbedPane;

    /**
     * Constructor for the MainPane class.
     */

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
                        ExecutePane executePane = UIGlobals.getGui().getMainPane().getExecutePane();
                        if (c == executePane) {
                            executePane.setWindowBounds();
                            UIGlobals.getGui().getMainPane().removeChangeListener(this);
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