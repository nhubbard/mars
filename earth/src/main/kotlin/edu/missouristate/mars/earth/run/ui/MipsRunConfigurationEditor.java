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

package edu.missouristate.mars.earth.run.ui;

import com.intellij.ide.util.BrowseFilesListener;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import edu.missouristate.mars.earth.lang.MipsFileType;
import edu.missouristate.mars.earth.run.MipsRunConfiguration;
import edu.missouristate.mars.earth.run.MipsRunConfigurationParams;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class MipsRunConfigurationEditor extends SettingsEditor<MipsRunConfiguration> implements MipsRunConfigurationParams {
    private final MipsRunConfiguration cfg;

    private JPanel rootPanel;
    private JCheckBox useMainCheck;
    private JSpinner maxStepsSpinner;
    private TextFieldWithBrowseButton mainFileField;
    private TextFieldWithBrowseButton workingDirField;
    private JCheckBox extendedInstructionsCheck;

    public MipsRunConfigurationEditor(MipsRunConfiguration cfg) {
        this.cfg = cfg;

        mainFileField.addBrowseFolderListener("MIPS Main File", "Choose the MIPS file containing the main function.", cfg.getProject(), getChooseMainFileDescriptor());
        workingDirField.addBrowseFolderListener("Working Directory", "Choose working directory.", cfg.getProject(), BrowseFilesListener.SINGLE_DIRECTORY_DESCRIPTOR);
    }

    private FileChooserDescriptor getChooseMainFileDescriptor() {
        FileChooserDescriptor chooseMainFile = new FileChooserDescriptor(
                true,
                false,
                false,
                false,
                false,
                false
        );
        chooseMainFile.withTreeRootVisible(true);
        chooseMainFile.withFileFilter(virtualFile -> virtualFile.getFileType() instanceof MipsFileType);
        chooseMainFile.setRoots(cfg.getProject().getBaseDir());

        return chooseMainFile;
    }

    @Override
    protected void resetEditorFrom(@NotNull MipsRunConfiguration runConfiguration) {
        MipsRunConfiguration.copyParams(runConfiguration, this);
    }

    @Override
    protected void applyEditorTo(@NotNull MipsRunConfiguration runConfiguration) throws ConfigurationException {
        MipsRunConfiguration.copyParams(this, runConfiguration);
    }

    @NotNull
    @Override
    protected JComponent createEditor() {
        return rootPanel;
    }

    @Override
    public String getMainFile() {
        return mainFileField.getText();
    }

    @Override
    public void setMainFile(String filename) {
        mainFileField.setText(filename);
    }

    @Override
    public String getWorkingDirectory() {
        return workingDirField.getText();
    }

    @Override
    public void setWorkingDirectory(String dir) {
        workingDirField.setText(dir);
    }

    @Override
    public int getMaxSteps() {
        return (Integer) maxStepsSpinner.getValue();
    }

    @Override
    public void setMaxSteps(int steps) {
        maxStepsSpinner.setValue(steps);
    }

    @Override
    public boolean isStartMain() {
        return useMainCheck.isSelected();
    }

    @Override
    public void setStartMain(boolean checked) {
        useMainCheck.setSelected(checked);
    }

    @Override
    public boolean isAllowExtendedInstructions() {
        return extendedInstructionsCheck.isSelected();
    }

    @Override
    public void setAllowExtendedInstructions(boolean checked) {
        extendedInstructionsCheck.setSelected(checked);
    }

    private void createUIComponents() {
        SpinnerNumberModel maxStepsModel = new SpinnerNumberModel();
        maxStepsModel.setMinimum(-1);
        maxStepsModel.setValue(-1);

        maxStepsSpinner = new JSpinner(maxStepsModel);
    }
}