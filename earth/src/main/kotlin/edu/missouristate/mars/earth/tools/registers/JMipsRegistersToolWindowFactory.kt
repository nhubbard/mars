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
package edu.missouristate.mars.earth.tools.registers

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import edu.missouristate.mars.CoreSettings
import edu.missouristate.mars.Globals.settings
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JCheckBox
import javax.swing.JPanel

// This file cannot be made in Kotlin: https://youtrack.jetbrains.com/issue/KTIJ-791
open class JMipsRegistersToolWindowFactory : ToolWindowFactory {
    private var toolWindow: ToolWindow? = null

    private var registersPanel: JPanel? = null
    private var coproc0Panel: JPanel? = null
    private var coproc1Panel: JPanel? = null
    private var registersTable: JBTable? = null
    private var coproc1Table: JBTable? = null
    private var coproc0Table: JBTable? = null
    private var showValuesInHexCheckBox: JCheckBox? = null
    private var settingsPanel: JPanel? = null

    init {
        showValuesInHexCheckBox!!.isSelected = settings.getBooleanSetting(CoreSettings.DISPLAY_VALUES_IN_HEX)

        showValuesInHexCheckBox!!.addActionListener(object : AbstractAction() {
            override fun actionPerformed(actionEvent: ActionEvent) {
                settings.setBooleanSetting(CoreSettings.DISPLAY_VALUES_IN_HEX, showValuesInHexCheckBox!!.isSelected)
            }
        })
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        this.toolWindow = toolWindow

        val contentFactory = ContentFactory.getInstance()

        val registers = contentFactory.createContent(registersPanel, "Registers", true)
        val coproc1 = contentFactory.createContent(coproc1Panel, "Coproc 1", true)
        val coproc0 = contentFactory.createContent(coproc0Panel, "Coproc 0", true)
        val settings = contentFactory.createContent(settingsPanel, "Settings", true)

        toolWindow.contentManager.addContent(registers)
        toolWindow.contentManager.addContent(coproc1)
        toolWindow.contentManager.addContent(coproc0)
        toolWindow.contentManager.addContent(settings)
    }

    private fun createUIComponents() {
        registersTable = MipsRegisterTable()
        coproc1Table = MipsCoproc1Table()
        coproc0Table = MipsCoproc0Table()
    }
}