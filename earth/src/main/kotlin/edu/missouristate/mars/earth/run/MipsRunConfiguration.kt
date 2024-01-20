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

package edu.missouristate.mars.earth.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil.*
import edu.missouristate.mars.earth.run.debugger.MipsDebugConsoleState
import edu.missouristate.mars.earth.run.ui.MipsRunConfigurationEditor
import org.jdom.Element

open class MipsRunConfiguration(
    project: Project,
    factory: ConfigurationFactory
) : LocatableConfigurationBase<MipsRunConfigurationParams>(
    project,
    factory,
    "edu.missouristate.mars.earth.run.MipsRunConfiguration"
), MipsRunConfigurationParams {
    companion object {
        @JvmStatic
        fun copyParams(from: MipsRunConfigurationParams, to: MipsRunConfigurationParams) {
            to.mainFile = from.mainFile
            to.maxSteps = from.maxSteps
            to.workingDirectory = from.workingDirectory
            to.isStartMain = from.isStartMain
            to.isAllowExtendedInstructions = from.isAllowExtendedInstructions
        }
    }

    override var mainFile: String = ""
    override var maxSteps: Int = -1
    override lateinit var workingDirectory: String
    override var isStartMain: Boolean = false
    override var isAllowExtendedInstructions: Boolean = false

    override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState? =
        if (executor.id == DefaultDebugExecutor.EXECUTOR_ID) {
            MipsDebugConsoleState(this, env, project)
        } else {
            MipsConsoleState(this, env, project)
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        MipsRunConfigurationEditor(this)

    override fun readExternal(element: Element) {
        super.readExternal(element)

        mainFile = readField(element, "MIPS_MAIN_FILE") ?: ""

        val steps = readField(element, "MIPS_MAX_STEPS")
        maxSteps = steps?.toIntOrNull() ?: -1

        val useMain = readField(element, "MIPS_START_MAIN")
        isStartMain = useMain?.toBooleanStrictOrNull() ?: false

        val extended = readField(element, "MIPS_EXTENDED_INSTRUCTIONS")
        isAllowExtendedInstructions = extended?.toBooleanStrictOrNull() ?: true
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        writeField(element, "MIPS_MAIN_FILE", mainFile)
        writeField(element, "MIPS_MAX_STEPS", maxSteps.toString())
        writeField(element, "MIPS_START_MAIN", isStartMain.toString())
        writeField(element, "MIPS_EXTENDED_INSTRUCTIONS", isAllowExtendedInstructions.toString())
    }

    override fun checkConfiguration() {}


}