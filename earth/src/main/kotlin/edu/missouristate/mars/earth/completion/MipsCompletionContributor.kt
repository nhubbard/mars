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

package edu.missouristate.mars.earth.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext
import edu.missouristate.mars.Globals
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.earth.icons.MipsIcons
import edu.missouristate.mars.earth.lang.psi.MipsFile

class MipsCompletionContributor : CompletionContributor() {
    init {
        // Completion for directives and instructions
        extend(
            CompletionType.BASIC,
            psiElement().withParent(MipsFile::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    params: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    suggestDirectives(result)
                    suggestInstructions(result)
                }
            }
        )
        // Completion for labels
        extend(
            CompletionType.BASIC,
            psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    params: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    suggestLabels(params, result)
                }
            }
        )
    }

    /**
     * Add all label definitions in current file.
     * TODO: Optimize using index.
     * TODO: Add tail text as documentation if there is a comment just above/below the label definition.
     * @param params Completion parameters to get the PsiFile
     * @param result Result set completions will be added to
     */
    private fun suggestLabels(params: CompletionParameters, result: CompletionResultSet) {
        if (params.originalFile !is MipsFile) return
        val file = params.originalFile as MipsFile
        for (label in file.getLabelDefinitions())
            result.addElement(
                LookupElementBuilder.create(label.name)
                    .withTypeText("Label")
                    .withBoldness(true)
                    .withIcon(MipsIcons.LABEL)
            )
    }

    /**
     * Add all directives to completions.
     * Note: A space will not be added at the end since some directives won't need arguments.
     * @param result Result set with completions
     */
    fun suggestDirectives(result: CompletionResultSet) {
        val directives = Directives.getDirectiveList()
        for (directive in directives) {
            result.addElement(
                LookupElementBuilder.create(directive.name)
                    .withTailText(" ${directive.description}", true)
                    .withTypeText("Directive", false)
                    .withInsertHandler(MipsInsertHandler())
                    .withBoldness(true)
                    .withIcon(MipsIcons.DIRECTIVE)
            )
        }
    }

    /**
     * Add all instructions to completions.
     * TODO: Respect setting whether to use extended instruction set or not.
     * @param result Result set with completions
     */
    fun suggestInstructions(result: CompletionResultSet) {
        var n = 1
        for (instruction in Globals.instructionSet.instructionList) {
            val name = String.format("%s%03d", instruction.name, n++)
            result.addElement(
                LookupElementBuilder.create(name)
                    .withTailText(" ${instruction.description}", true)
                    .withTypeText("Instruction")
                    .withInsertHandler(MipsInsertHandler())
                    .withBoldness(true)
                    .withPresentableText(instruction.exampleFormat)
                    .withIcon(MipsIcons.INSTRUCTION)
            )
        }
    }

    class MipsInsertHandler : InsertHandler<LookupElement> {
        private val instructionId = "^(\\D+)\\d{3} $".toRegex()

        override fun handleInsert(context: InsertionContext, element: LookupElement) {
            val editor = context.editor
            val document = editor.document

            val start = context.startOffset
            val tail = context.tailOffset

            val text = document.text.substring(start, context.tailOffset)

            if (text.startsWith(".")) {
                document.replaceString(start, tail, text.substring(1))
            } else {
                if (text.matches(instructionId)) document.replaceString(tail - 4, tail - 1, "")
            }
        }
    }
}