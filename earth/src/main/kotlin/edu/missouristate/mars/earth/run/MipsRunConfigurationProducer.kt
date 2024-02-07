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

@file:Suppress("UnstableApiUsage")

package edu.missouristate.mars.earth.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import edu.missouristate.mars.earth.lang.psi.MipsFile
import edu.missouristate.mars.earth.lang.psi.MipsLabelDefinition

open class MipsRunConfigurationProducer protected constructor() : LazyRunConfigurationProducer<MipsRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = object : ConfigurationFactory() {
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            MipsRunConfiguration(project, this)

        override fun getType(): ConfigurationType = MipsRunConfigurationType()

        override fun getId(): String = "MipsConfigurationFactory"
    }

    override fun setupConfigurationFromContext(
        cfg: MipsRunConfiguration,
        context: ConfigurationContext,
        ref: Ref<PsiElement>
    ): Boolean {
        val psiElement = ref.get()
        if (psiElement == null || !psiElement.isValid) return false
        val file = psiElement.containingFile
        if (file !is MipsFile) return false
        val mainFile = file.virtualFile.canonicalPath!!
        cfg.mainFile = mainFile
        cfg.isAllowExtendedInstructions = true
        cfg.isStartMain = false
        cfg.name = file.virtualFile.name + ":main"
        val iter = SyntaxTraverser
            .psiTraverser(file)
            .children(file)
            .filter(MipsLabelDefinition::class.java)
            .iterator()
        for (label in iter) {
            if (label.name == "main") {
                cfg.isStartMain = true
                break
            }
        }
        return true
    }

    override fun isConfigurationFromContext(cfg: MipsRunConfiguration, context: ConfigurationContext): Boolean {
        val psiElement = context.psiLocation.takeIf { it?.isValid == true } ?: return false
        val file = psiElement.containingFile
        if (file !is MipsFile) return false
        val cfgName = file.virtualFile.name + ":main"
        return cfg.name == cfgName
    }
}