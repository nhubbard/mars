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

package edu.missouristate.mars.earth.tools.registers;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.table.JBTable;
import edu.missouristate.mars.CoreSettings;
import edu.missouristate.mars.Globals;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.event.ActionEvent;

// This file cannot be made in Kotlin: https://youtrack.jetbrains.com/issue/KTIJ-791
public class MipsRegistersToolWindowFactory implements ToolWindowFactory {
    private ToolWindow toolWindow;

    private JPanel registersPanel;
    private JPanel coproc0Panel;
    private JPanel coproc1Panel;
    private JBTable registersTable;
    private JBTable coproc1Table;
    private JBTable coproc0Table;
    private JCheckBox showValuesInHexCheckBox;
    private JPanel settingsPanel;

    public MipsRegistersToolWindowFactory() {
        showValuesInHexCheckBox.setSelected(Globals.getSettings().getBooleanSetting(CoreSettings.DISPLAY_VALUES_IN_HEX));

        showValuesInHexCheckBox.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                Globals.getSettings().setBooleanSetting(CoreSettings.DISPLAY_VALUES_IN_HEX, showValuesInHexCheckBox.isSelected());
            }
        });
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        this.toolWindow = toolWindow;

        ContentFactory contentFactory = ContentFactory.getInstance();

        Content registers = contentFactory.createContent(registersPanel, "Registers", true);
        Content coproc1 = contentFactory.createContent(coproc1Panel, "Coproc 1", true);
        Content coproc0 = contentFactory.createContent(coproc0Panel, "Coproc 0", true);
        Content settings = contentFactory.createContent(settingsPanel, "Settings", true);

        toolWindow.getContentManager().addContent(registers);
        toolWindow.getContentManager().addContent(coproc1);
        toolWindow.getContentManager().addContent(coproc0);
        toolWindow.getContentManager().addContent(settings);
    }

    private void createUIComponents() {
        registersTable = new MipsRegisterTable();
        coproc1Table = new MipsCoproc1Table();
        coproc0Table = new MipsCoproc0Table();
    }
}