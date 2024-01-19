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

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import edu.missouristate.mars.earth.lang.lexer.MipsLexerAdapter
import edu.missouristate.mars.earth.lang.psi.MipsElementTypes
import edu.missouristate.mars.earth.lang.psi.MipsTokenTypes

class MipsSyntaxHighlighter : SyntaxHighlighterBase() {
    companion object {
        @JvmField val ERROR = TextAttributesKey.createTextAttributesKey(
            "MIPS_ERROR",
            DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE
        )
        @JvmField val COMMENT = TextAttributesKey.createTextAttributesKey(
            "MIPS_COMMENT",
            DefaultLanguageHighlighterColors.LINE_COMMENT
        )
        @JvmField val OPERATOR = TextAttributesKey.createTextAttributesKey(
            "MIPS_OPERATOR",
            DefaultLanguageHighlighterColors.KEYWORD
        )
        @JvmField val DIRECTIVE = TextAttributesKey.createTextAttributesKey(
            "MIPS_DIRECTIVE",
            DefaultLanguageHighlighterColors.STATIC_FIELD
        )
        @JvmField val LABEL = TextAttributesKey.createTextAttributesKey(
            "MIPS_LABEL",
            DefaultLanguageHighlighterColors.IDENTIFIER
        )
        @JvmField val MAIN_LABEL = TextAttributesKey.createTextAttributesKey(
            "MIPS_MAIN_LABEL",
            DefaultLanguageHighlighterColors.STATIC_METHOD
        )
        @JvmField val STRING = TextAttributesKey.createTextAttributesKey(
            "MIPS_STRING",
            DefaultLanguageHighlighterColors.STRING
        )
        @JvmField val NUMBER = TextAttributesKey.createTextAttributesKey(
            "MIPS_NUMBER",
            DefaultLanguageHighlighterColors.NUMBER
        )
        @JvmField val REGISTER = TextAttributesKey.createTextAttributesKey(
            "MIPS_REGISTER",
            DefaultLanguageHighlighterColors.INSTANCE_FIELD
        )
    }

    override fun getHighlightingLexer(): Lexer = MipsLexerAdapter()

    override fun getTokenHighlights(type: IElementType): Array<TextAttributesKey> = when {
        type == TokenType.BAD_CHARACTER -> pack(ERROR)
        MipsTokenTypes.COMMENTS.contains(type) -> pack(COMMENT)
        type == MipsElementTypes.OPERATOR -> pack(OPERATOR)
        type == MipsElementTypes.DIRECTIVE -> pack(DIRECTIVE)
        type == MipsElementTypes.IDENTIFIER -> pack(LABEL)
        MipsTokenTypes.STRINGS.contains(type) -> pack(STRING)
        MipsTokenTypes.NUMBERS.contains(type) -> pack(NUMBER)
        MipsTokenTypes.REGISTERS.contains(type) -> pack(REGISTER)
        else -> emptyArray()
    }
}