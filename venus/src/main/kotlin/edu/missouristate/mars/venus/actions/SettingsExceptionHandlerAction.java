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

import edu.missouristate.mars.*;
import edu.missouristate.mars.venus.VenusUI;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.*;
import java.io.*;

/**
 * Action class for the Settings menu item for optionally loading a MIPS exception handler.
 */
public class SettingsExceptionHandlerAction extends GuiAction {

    JDialog exceptionHandlerDialog;
    JCheckBox exceptionHandlerSetting;
    JButton exceptionHandlerSelectionButton;
    JTextField exceptionHandlerDisplay;

    boolean initialSelected; // state of check box when dialog initiated.
    String initialPathname;  // selected exception handler when dialog initiated.

    public SettingsExceptionHandlerAction(String name, Icon icon, String descrip,
                                          Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    // launch dialog for setting and filename specification
    public void actionPerformed(ActionEvent e) {
        initialSelected = Globals.getSettings().getBooleanSetting(CoreSettings.EXCEPTION_HANDLER_ENABLED);
        initialPathname = Globals.getSettings().getExceptionHandler();
        exceptionHandlerDialog = new JDialog(Globals.getGui(), "Exception Handler", true);
        exceptionHandlerDialog.setContentPane(buildDialogPanel());
        exceptionHandlerDialog.setDefaultCloseOperation(
                JDialog.DO_NOTHING_ON_CLOSE);
        exceptionHandlerDialog.addWindowListener(
                new WindowAdapter() {
                    public void windowClosing(WindowEvent we) {
                        closeDialog();
                    }
                });
        exceptionHandlerDialog.pack();
        exceptionHandlerDialog.setLocationRelativeTo(Globals.getGui());
        exceptionHandlerDialog.setVisible(true);
    }

    // The dialog box that appears when menu item is selected.
    private JPanel buildDialogPanel() {
        JPanel contents = new JPanel(new BorderLayout(20, 20));
        contents.setBorder(new EmptyBorder(10, 10, 10, 10));
        // Top row - the check box for setting...
        exceptionHandlerSetting = new JCheckBox("Include this exception handler file in all assemble operations");
        exceptionHandlerSetting.setSelected(Globals.getSettings().getBooleanSetting(CoreSettings.EXCEPTION_HANDLER_ENABLED));
        exceptionHandlerSetting.addActionListener(new ExceptionHandlerSettingAction());
        contents.add(exceptionHandlerSetting, BorderLayout.NORTH);
        // Middle row - the button and text field for exception handler file selection
        JPanel specifyHandlerFile = new JPanel();
        exceptionHandlerSelectionButton = new JButton("Browse");
        exceptionHandlerSelectionButton.setEnabled(exceptionHandlerSetting.isSelected());
        exceptionHandlerSelectionButton.addActionListener(new ExceptionHandlerSelectionAction());
        exceptionHandlerDisplay = new JTextField(Globals.getSettings().getExceptionHandler(), 30);
        exceptionHandlerDisplay.setEditable(false);
        exceptionHandlerDisplay.setEnabled(exceptionHandlerSetting.isSelected());
        specifyHandlerFile.add(exceptionHandlerSelectionButton);
        specifyHandlerFile.add(exceptionHandlerDisplay);
        contents.add(specifyHandlerFile, BorderLayout.CENTER);
        // Bottom row - the control buttons for OK and Cancel
        Box controlPanel = Box.createHorizontalBox();
        JButton okButton = new JButton("OK");
        okButton.addActionListener(
                e -> {
                    performOK();
                    closeDialog();
                });
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(
                e -> closeDialog());
        controlPanel.add(Box.createHorizontalGlue());
        controlPanel.add(okButton);
        controlPanel.add(Box.createHorizontalGlue());
        controlPanel.add(cancelButton);
        controlPanel.add(Box.createHorizontalGlue());
        contents.add(controlPanel, BorderLayout.SOUTH);
        return contents;
    }

    // User has clicked "OK" button, so record status of the checkbox and text field.
    private void performOK() {
        boolean finalSelected = exceptionHandlerSetting.isSelected();
        String finalPathname = exceptionHandlerDisplay.getText();
        // If nothing has changed then don't modify setting variables or properties file.
        if (initialSelected != finalSelected
                || initialPathname == null && finalPathname != null
                || initialPathname != null && !initialPathname.equals(finalPathname)) {
            Globals.getSettings().setBooleanSetting(CoreSettings.EXCEPTION_HANDLER_ENABLED, finalSelected);
            if (finalSelected) {
                Globals.getSettings().setExceptionHandler(finalPathname);
            }
        }
    }

    // We're finished with this modal dialog.
    private void closeDialog() {
        exceptionHandlerDialog.setVisible(false);
        exceptionHandlerDialog.dispose();
    }


    /////////////////////////////////////////////////////////////////////////////////
    // Associated action class: exception handler setting.  Attached to check box.
    private class ExceptionHandlerSettingAction implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            boolean selected = ((JCheckBox) e.getSource()).isSelected();
            exceptionHandlerSelectionButton.setEnabled(selected);
            exceptionHandlerDisplay.setEnabled(selected);
        }
    }


    /////////////////////////////////////////////////////////////////////////////////
    // Associated action class: selecting exception handler file.  Attached to handler selector.
    private class ExceptionHandlerSelectionAction implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            JFileChooser chooser = new JFileChooser();
            String pathname = Globals.getSettings().getExceptionHandler();
            if (pathname != null) {
                File file = new File(pathname);
                if (file.exists()) chooser.setSelectedFile(file);
            }
            int result = chooser.showOpenDialog(Globals.getGui());
            if (result == JFileChooser.APPROVE_OPTION) {
                pathname = chooser.getSelectedFile().getPath();//.replaceAll("\\\\","/");
                exceptionHandlerDisplay.setText(pathname);
            }
        }
    }

}