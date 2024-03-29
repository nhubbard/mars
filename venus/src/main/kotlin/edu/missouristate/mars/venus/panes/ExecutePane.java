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

import edu.missouristate.mars.*;
import edu.missouristate.mars.venus.*;
import edu.missouristate.mars.venus.windows.*;
import venus.windows.*;

import javax.swing.*;
import java.awt.*;

/**
 * Container for the execution-related windows.  Currently displayed as a tabbed pane.
 *
 * @author Sanderson and Team JSpim
 */

public class ExecutePane extends JDesktopPane {
    private final RegistersWindow registerValues;
    private final Coprocessor1Window coprocessor1Values;
    private final Coprocessor0Window coprocessor0Values;
    private final DataSegmentWindow dataSegment;
    private final TextSegmentWindow textSegment;
    private final LabelsWindow labelValues;
    private final VenusUI mainUI;
    private final NumberDisplayBaseChooserUI valueDisplayBase;
    private final NumberDisplayBaseChooserUI addressDisplayBase;
    private boolean labelWindowVisible;

    /**
     * initialize the Execute pane with major components
     *
     * @param mainUI   the parent GUI
     * @param regs     window containing integer register set
     * @param cop1Regs window containing Coprocessor 1 register set
     * @param cop0Regs window containing Coprocessor 0 register set
     */

    public ExecutePane(VenusUI mainUI, RegistersWindow regs, Coprocessor1Window cop1Regs, Coprocessor0Window cop0Regs) {
        this.mainUI = mainUI;
        // Although these are displayed in Data Segment, they apply to all three internal
        // windows within the Execute pane.  So they will be housed here.
        addressDisplayBase = new NumberDisplayBaseChooserUI("Hexadecimal Addresses",
                Globals.getSettings().getBooleanSetting(CoreSettings.DISPLAY_ADDRESSES_IN_HEX));
        valueDisplayBase = new NumberDisplayBaseChooserUI("Hexadecimal Values",
                Globals.getSettings().getBooleanSetting(CoreSettings.DISPLAY_VALUES_IN_HEX));//VenusUI.DEFAULT_NUMBER_BASE);
        addressDisplayBase.setToolTipText("If checked, displays all memory addresses in hexadecimal.  Otherwise, decimal.");
        valueDisplayBase.setToolTipText("If checked, displays all memory and register contents in hexadecimal.  Otherwise, decimal.");
        NumberDisplayBaseChooserUI[] choosers = {addressDisplayBase, valueDisplayBase};
        registerValues = regs;
        coprocessor1Values = cop1Regs;
        coprocessor0Values = cop0Regs;
        textSegment = new TextSegmentWindow();
        dataSegment = new DataSegmentWindow(choosers);
        labelValues = new LabelsWindow();
        labelWindowVisible = Globals.getSettings().getBooleanSetting(CoreSettings.LABEL_WINDOW_VISIBILITY);
        this.add(textSegment);  // these 3 LOC moved up.  DPS 3-Sept-2014
        this.add(dataSegment);
        this.add(labelValues);
        textSegment.pack();   // these 3 LOC added.  DPS 3-Sept-2014
        dataSegment.pack();
        labelValues.pack();
        textSegment.setVisible(true);
        dataSegment.setVisible(true);
        labelValues.setVisible(labelWindowVisible);

    }

    /**
     * This method will set the bounds of this JDesktopPane's internal windows
     * relative to the current size of this JDesktopPane.  Such an operation
     * cannot be adequately done at constructor time because the actual
     * size of the desktop pane window is not yet established.  Layout manager
     * is not a good option here because JDesktopPane does not work well with
     * them (the whole idea of using JDesktopPane with internal frames is to
     * have mini-frames that you can resize, move around, minimize, etc).  This
     * method should be invoked only once: the first time the Execute tab is
     * selected (a change listener invokes it).  We do not want it invoked
     * on subsequent tab selections; otherwise, user manipulations of the
     * internal frames would be lost the next time execute tab is selected.
     */
    public void setWindowBounds() {

        int fullWidth = this.getSize().width - this.getInsets().left - this.getInsets().right;
        int fullHeight = this.getSize().height - this.getInsets().top - this.getInsets().bottom;
        int halfHeight = fullHeight / 2;
        Dimension textDim = new Dimension((int) (fullWidth * .75), halfHeight);
        Dimension dataDim = new Dimension(fullWidth, halfHeight);
        Dimension lablDim = new Dimension((int) (fullWidth * .25), halfHeight);
        Dimension textFullDim = new Dimension(fullWidth, halfHeight);
        dataSegment.setBounds(0, textDim.height + 1, dataDim.width, dataDim.height);
        if (labelWindowVisible) {
            textSegment.setBounds(0, 0, textDim.width, textDim.height);
            labelValues.setBounds(textDim.width + 1, 0, lablDim.width, lablDim.height);
        } else {
            textSegment.setBounds(0, 0, textFullDim.width, textFullDim.height);
            labelValues.setBounds(0, 0, 0, 0);
        }
    }

    /**
     * Show or hide the label window (symbol table).  If visible, it is displayed
     * to the right of the text segment and the latter is shrunk accordingly.
     *
     * @param visibility set to true or false
     */

    public void setLabelWindowVisibility(boolean visibility) {
        if (!visibility && labelWindowVisible) {
            labelWindowVisible = false;
            textSegment.setVisible(false);
            labelValues.setVisible(false);
            setWindowBounds();
            textSegment.setVisible(true);
        } else if (visibility && !labelWindowVisible) {
            labelWindowVisible = true;
            textSegment.setVisible(false);
            setWindowBounds();
            textSegment.setVisible(true);
            labelValues.setVisible(true);
        }
    }

    /**
     * Clears out all components of the Execute tab: text segment
     * display, data segment display, label display and register display.
     * This will typically be done upon File->Close, Open, New.
     */

    public void clearPane() {
        this.getTextSegmentWindow().clearWindow();
        this.getDataSegmentWindow().clearWindow();
        this.getRegistersWindow().clearWindow();
        this.getCoprocessor1Window().clearWindow();
        this.getCoprocessor0Window().clearWindow();
        this.getLabelsWindow().clearWindow();
        // seems to be required, to display cleared Execute tab contents...
        if (mainUI.getMainPane().getSelectedComponent() == this) {
            mainUI.getMainPane().setSelectedComponent(mainUI.getMainPane().getEditTabbedPane());
            mainUI.getMainPane().setSelectedComponent(this);
        }
    }

    /**
     * Access the text segment window.
     */
    public TextSegmentWindow getTextSegmentWindow() {
        return textSegment;
    }

    /**
     * Access the data segment window.
     */
    public DataSegmentWindow getDataSegmentWindow() {
        return dataSegment;
    }

    /**
     * Access the register values window.
     */
    public RegistersWindow getRegistersWindow() {
        return registerValues;
    }

    /**
     * Access the coprocessor1 values window.
     */
    public Coprocessor1Window getCoprocessor1Window() {
        return coprocessor1Values;
    }

    /**
     * Access the coprocessor0 values window.
     */
    public Coprocessor0Window getCoprocessor0Window() {
        return coprocessor0Values;
    }

    /**
     * Access the label values window.
     */
    public LabelsWindow getLabelsWindow() {
        return labelValues;
    }

    /**
     * Retrieve the number system base for displaying values (mem/register contents)
     */
    public int getValueDisplayBase() {
        return valueDisplayBase.getBase();
    }

    /**
     * Retrieve the number system base for displaying memory addresses
     */
    public int getAddressDisplayBase() {
        return addressDisplayBase.getBase();
    }

    /**
     * Retrieve component used to set numerical base (10 or 16) of data value display.
     *
     * @return the chooser
     */
    public NumberDisplayBaseChooserUI getValueDisplayBaseChooser() {
        return valueDisplayBase;
    }

    /**
     * Retrieve component used to set numerical base (10 or 16) of address display.
     *
     * @return the chooser
     */
    public NumberDisplayBaseChooserUI getAddressDisplayBaseChooser() {
        return addressDisplayBase;
    }

    /**
     * Update display of columns based on state of given chooser.  Normally
     * called only by the chooser's ItemListener.
     *
     * @param chooser the GUI object manipulated by the user to change number base
     */
    public void numberDisplayBaseChanged(NumberDisplayBaseChooserUI chooser) {
        if (chooser == valueDisplayBase) {
            // Have all internal windows update their value columns
            registerValues.updateRegisters();
            coprocessor1Values.updateRegisters();
            coprocessor0Values.updateRegisters();
            dataSegment.updateValues();
            textSegment.updateBasicStatements();
        } else { // addressDisplayBase
            // Have all internal windows update their address columns
            dataSegment.updateDataAddresses();
            labelValues.updateLabelAddresses();
            textSegment.updateCodeAddresses();
            textSegment.updateBasicStatements();
        }
    }

}