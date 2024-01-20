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
package edu.missouristate.mars.earth.run.ui

import com.intellij.ide.util.BrowseFilesListener
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import edu.missouristate.mars.earth.lang.MipsFileType
import edu.missouristate.mars.earth.run.MipsRunConfiguration
import edu.missouristate.mars.earth.run.MipsRunConfiguration.Companion.copyParams
import edu.missouristate.mars.earth.run.MipsRunConfigurationParams
import javax.swing.*

class MipsRunConfigurationEditor(private val cfg: MipsRunConfiguration) : SettingsEditor<MipsRunConfiguration>(), MipsRunConfigurationParams {
    private var rootPanel: JPanel? = null
    private var useMainCheck: JCheckBox? = null
    private var maxStepsSpinner: JSpinner? = null
    private var mainFileField: TextFieldWithBrowseButton? = null
    private var workingDirField: TextFieldWithBrowseButton? = null
    private var extendedInstructionsCheck: JCheckBox? = null

    init {
        mainFileField!!.addBrowseFolderListener(
            "MIPS Main File",
            "Choose the MIPS file containing the main function.",
            cfg.project,
            chooseMainFileDescriptor
        )
        workingDirField!!.addBrowseFolderListener(
            "Working Directory",
            "Choose working directory.",
            cfg.project,
            BrowseFilesListener.SINGLE_DIRECTORY_DESCRIPTOR
        )
    }

    private val chooseMainFileDescriptor: FileChooserDescriptor
        get() {
            val chooseMainFile = FileChooserDescriptor(true, false, false, false, false, false)
            chooseMainFile.withTreeRootVisible(true)
            chooseMainFile.withFileFilter { it.fileType is MipsFileType }
            chooseMainFile.setRoots(cfg.project.baseDir)
            return chooseMainFile
        }

    override fun resetEditorFrom(runConfiguration: MipsRunConfiguration) {
        copyParams(runConfiguration, this)
    }

    @Throws(ConfigurationException::class)
    override fun applyEditorTo(runConfiguration: MipsRunConfiguration) {
        copyParams(this, runConfiguration)
    }

    override fun createEditor(): JComponent {
        return rootPanel!!
    }

    override var mainFile: String
        get() = mainFileField!!.text
        set(filename) {
            mainFileField!!.text = filename
        }

    override var workingDirectory: String
        get() = workingDirField!!.text
        set(dir) {
            workingDirField!!.text = dir
        }

    override var maxSteps: Int
        get() = maxStepsSpinner!!.value as Int
        set(steps) {
            maxStepsSpinner!!.value = steps
        }

    override var isStartMain: Boolean
        get() = useMainCheck!!.isSelected
        set(checked) {
            useMainCheck!!.isSelected = checked
        }

    override var isAllowExtendedInstructions: Boolean
        get() = extendedInstructionsCheck!!.isSelected
        set(checked) {
            extendedInstructionsCheck!!.isSelected = checked
        }

    private fun createUIComponents() {
        val maxStepsModel = SpinnerNumberModel()
        maxStepsModel.minimum = -1
        maxStepsModel.value = -1

        maxStepsSpinner = JSpinner(maxStepsModel)
    }
}