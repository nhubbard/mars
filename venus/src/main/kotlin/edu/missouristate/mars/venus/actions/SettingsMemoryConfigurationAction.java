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

package edu.missouristate.mars.venus.actions;

import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;
import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.venus.FileStatus;
import edu.missouristate.mars.venus.VenusUI;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.*;

/**
 * Action class for the Settings menu item for text editor settings.
 */
public class SettingsMemoryConfigurationAction extends GuiAction {

    JDialog configDialog;
    JSlider fontSizeSelector;
    JTextField fontSizeDisplay;
    final SettingsMemoryConfigurationAction thisAction;

    // Used to determine upon OK, whether or not anything has changed.
    String initialFontFamily, initialFontStyle, initialFontSize;

    /**
     * Create a new SettingsEditorAction.  Has all the GuiAction parameters.
     */
    public SettingsMemoryConfigurationAction(String name, Icon icon, String descrip,
                                             Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
        thisAction = this;
    }

    /**
     * When this action is triggered, launch a dialog to view and modify
     * editor settings.
     */
    public void actionPerformed(ActionEvent e) {
        configDialog = new MemoryConfigurationDialog(Globals.getGui(), "MIPS Memory Configuration", true);
        configDialog.setVisible(true);
    }

    //////////////////////////////////////////////////////////////////////////////
    //
    //   Private class to do all the work!
    //
    private class MemoryConfigurationDialog extends JDialog implements ActionListener {
        JTextField[] addressDisplay;
        JLabel[] nameDisplay;
        ConfigurationButton selectedConfigurationButton, initialConfigurationButton;

        public MemoryConfigurationDialog(Frame owner, String title, boolean modality) {
            super(owner, title, modality);
            this.setContentPane(buildDialogPanel());
            this.setDefaultCloseOperation(
                    JDialog.DO_NOTHING_ON_CLOSE);
            this.addWindowListener(
                    new WindowAdapter() {
                        public void windowClosing(WindowEvent we) {
                            performClose();
                        }
                    });
            this.pack();
            this.setLocationRelativeTo(owner);
        }

        private JPanel buildDialogPanel() {
            JPanel dialogPanel = new JPanel(new BorderLayout());
            dialogPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

            JPanel configInfo = new JPanel(new FlowLayout());
            MemoryConfigurations.buildConfigurationCollection();
            configInfo.add(buildConfigChooser());
            configInfo.add(buildConfigDisplay());
            dialogPanel.add(configInfo);
            dialogPanel.add(buildControlPanel(), BorderLayout.SOUTH);
            return dialogPanel;
        }

        private Component buildConfigChooser() {
            JPanel chooserPanel = new JPanel(new GridLayout(4, 1));
            ButtonGroup choices = new ButtonGroup();
            Iterator<MemoryConfiguration> configurationsIterator = MemoryConfigurations.getConfigurationsIterator();
            while (configurationsIterator.hasNext()) {
                MemoryConfiguration config = configurationsIterator.next();
                ConfigurationButton button = new ConfigurationButton(config);
                button.addActionListener(this);
                if (button.isSelected()) {
                    this.selectedConfigurationButton = button;
                    this.initialConfigurationButton = button;
                }
                choices.add(button);
                chooserPanel.add(button);
            }
            chooserPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.BLACK)
                    , "Configuration"));
            return chooserPanel;
        }


        private Component buildConfigDisplay() {
            JPanel displayPanel = new JPanel();
            MemoryConfiguration config = MemoryConfigurations.getCurrentConfiguration();
            String[] configurationItemNames = config.configurationItemNames;
            int numItems = configurationItemNames.length;
            JPanel namesPanel = new JPanel(new GridLayout(numItems, 1));
            JPanel valuesPanel = new JPanel(new GridLayout(numItems, 1));
            Font monospaced = new Font("Monospaced", Font.PLAIN, 12);
            nameDisplay = new JLabel[numItems];
            addressDisplay = new JTextField[numItems];
            for (int i = 0; i < numItems; i++) {
                nameDisplay[i] = new JLabel();
                addressDisplay[i] = new JTextField();
                addressDisplay[i].setEditable(false);
                addressDisplay[i].setFont(monospaced);
            }
            // Display vertically from high to low memory addresses so
            // add the components in reverse order.
            for (int i = addressDisplay.length - 1; i >= 0; i--) {
                namesPanel.add(nameDisplay[i]);
                valuesPanel.add(addressDisplay[i]);
            }
            setConfigDisplay(config);
            Box columns = Box.createHorizontalBox();
            columns.add(valuesPanel);
            columns.add(Box.createHorizontalStrut(6));
            columns.add(namesPanel);
            displayPanel.add(columns);
            return displayPanel;
        }


        // Carry out action for the radio buttons.
        public void actionPerformed(ActionEvent e) {
            MemoryConfiguration config = ((ConfigurationButton) e.getSource()).getConfiguration();
            setConfigDisplay(config);
            this.selectedConfigurationButton = (ConfigurationButton) e.getSource();
        }


        // Row of control buttons to be placed along the button of the dialog
        private Component buildControlPanel() {
            Box controlPanel = Box.createHorizontalBox();
            JButton okButton = new JButton("Apply and Close");
            okButton.setToolTipText(SettingsHighlightingAction.CLOSE_TOOL_TIP_TEXT);
            okButton.addActionListener(
                    e -> {
                        performApply();
                        performClose();
                    });
            JButton applyButton = new JButton("Apply");
            applyButton.setToolTipText(SettingsHighlightingAction.APPLY_TOOL_TIP_TEXT);
            applyButton.addActionListener(
                    e -> performApply());
            JButton cancelButton = new JButton("Cancel");
            cancelButton.setToolTipText(SettingsHighlightingAction.CANCEL_TOOL_TIP_TEXT);
            cancelButton.addActionListener(
                    e -> performClose());
            JButton resetButton = new JButton("Reset");
            resetButton.setToolTipText(SettingsHighlightingAction.RESET_TOOL_TIP_TEXT);
            resetButton.addActionListener(
                    e -> performReset());
            controlPanel.add(Box.createHorizontalGlue());
            controlPanel.add(okButton);
            controlPanel.add(Box.createHorizontalGlue());
            controlPanel.add(applyButton);
            controlPanel.add(Box.createHorizontalGlue());
            controlPanel.add(cancelButton);
            controlPanel.add(Box.createHorizontalGlue());
            controlPanel.add(resetButton);
            controlPanel.add(Box.createHorizontalGlue());
            return controlPanel;
        }

        private void performApply() {
            if (MemoryConfigurations.setCurrentConfiguration(this.selectedConfigurationButton.getConfiguration())) {
                Globals.getSettings().setMemoryConfiguration(this.selectedConfigurationButton.getConfiguration().configurationIdentifier);
                Globals.getGui().getRegistersPane().getRegistersWindow().clearHighlighting();
                Globals.getGui().getRegistersPane().getRegistersWindow().updateRegisters();
                Globals.getGui().getMainPane().getExecutePane().getDataSegmentWindow().updateBaseAddressComboBox();
                // 21 July 2009 Re-assemble if the situation demands it to maintain consistency.
                if (FileStatus.Companion.getStatus() == FileStatus.StatusType.RUNNABLE ||
                        FileStatus.Companion.getStatus() == FileStatus.StatusType.RUNNING ||
                        FileStatus.Companion.getStatus() == FileStatus.StatusType.TERMINATED) {
                    // Stop execution if executing -- should NEVER happen because this
                    // Action's widget is disabled during MIPS execution.
                    if (FileStatus.Companion.getStatus() == FileStatus.StatusType.RUNNING) {
                        Simulator.getInstance().stopExecution(thisAction);
                    }
                    Globals.getGui().getRunAssembleAction().actionPerformed(null);
                }
            }
        }

        private void performClose() {
            this.setVisible(false);
            this.dispose();
        }

        private void performReset() {
            this.selectedConfigurationButton = this.initialConfigurationButton;
            this.selectedConfigurationButton.setSelected(true);
            setConfigDisplay(this.selectedConfigurationButton.getConfiguration());
        }


        // Set name values in JLabels and address values in the JTextFields
        private void setConfigDisplay(MemoryConfiguration config) {
            String[] configurationItemNames = config.configurationItemNames;
            int[] configurationItemValues = config.configurationItemValues;
            // Will use TreeMap to extract list of address-name pairs sorted by
            // hex-stringified address. This will correctly handle kernel addresses,
            // whose int values are negative and thus normal sorting yields incorrect
            // results.  There can be duplicate addresses, so I concatenate the name
            // onto the address to make each key unique.  Then slice off the name upon
            // extraction.
            TreeMap<String, String> treeSortedByAddress = new TreeMap<>();
            for (int i = 0; i < configurationItemValues.length; i++) {
                treeSortedByAddress.put(Binary.intToHexString(configurationItemValues[i]) + configurationItemNames[i], configurationItemNames[i]);
            }
            Iterator<Map.Entry<String, String>> setSortedByAddress = treeSortedByAddress.entrySet().iterator();
            Map.Entry<String, String> pair;
            int addressStringLength = Binary.intToHexString(configurationItemValues[0]).length();
            for (int i = 0; i < configurationItemValues.length; i++) {
                pair = setSortedByAddress.next();
                nameDisplay[i].setText(pair.getValue());
                addressDisplay[i].setText(pair.getKey().substring(0, addressStringLength));
            }
        }

    }

    // Handy class to connect button to its configuration...
    private static class ConfigurationButton extends JRadioButton {
        private final MemoryConfiguration configuration;

        public ConfigurationButton(MemoryConfiguration config) {
            super(config.configurationName, config == MemoryConfigurations.getCurrentConfiguration());
            this.configuration = config;
        }

        public MemoryConfiguration getConfiguration() {
            return configuration;
        }

    }

}