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

package edu.missouristate.mars.earth.editor

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import edu.missouristate.mars.earth.editor.MipsSyntaxHighlighter.Companion.COMMENT
import edu.missouristate.mars.earth.editor.MipsSyntaxHighlighter.Companion.DIRECTIVE
import edu.missouristate.mars.earth.editor.MipsSyntaxHighlighter.Companion.ERROR
import edu.missouristate.mars.earth.editor.MipsSyntaxHighlighter.Companion.LABEL
import edu.missouristate.mars.earth.editor.MipsSyntaxHighlighter.Companion.NUMBER
import edu.missouristate.mars.earth.editor.MipsSyntaxHighlighter.Companion.OPERATOR
import edu.missouristate.mars.earth.editor.MipsSyntaxHighlighter.Companion.REGISTER
import edu.missouristate.mars.earth.editor.MipsSyntaxHighlighter.Companion.STRING
import edu.missouristate.mars.earth.icons.MipsIcons
import javax.swing.Icon

class MipsColorSettingsPage : ColorSettingsPage {
    private val attributes = arrayOf(
        AttributesDescriptor("Illegal character", ERROR),
        AttributesDescriptor("Comment", COMMENT),
        AttributesDescriptor("String", STRING),
        AttributesDescriptor("Number", NUMBER),
        AttributesDescriptor("Register", REGISTER),
        AttributesDescriptor("Operator", OPERATOR),
        AttributesDescriptor("Directive", DIRECTIVE),
        AttributesDescriptor("Label", LABEL)
    )

    private val attributesKeyMap = hashMapOf(
        "d" to DIRECTIVE, "l" to LABEL, "o" to OPERATOR
    )

    override fun getIcon(): Icon = MipsIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = MipsSyntaxHighlighter()
    override fun getDemoText(): String =
        """# this is a comment!
        .data
        myString: .asciiz "Hello, world!\n"
        
        .text
        
        main
        j exit
        
        exit
          li ${"$"}v0, 10
          syscall
        """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey>? =
        attributesKeyMap

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = attributes

    override fun getColorDescriptors(): Array<ColorDescriptor> = arrayOf()

    override fun getDisplayName(): String = "MIPS"
}