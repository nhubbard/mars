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

package edu.missouristate.mars.earth.lang.psi

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import edu.missouristate.mars.assembler.Token
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.earth.MipsException
import edu.missouristate.mars.earth.lang.MipsLanguage

class MipsElementType(debug: String) : IElementType(debug, MipsLanguage.INSTANCE) {
    companion object {
        @JvmStatic
        @Throws(MipsException::class)
        fun fromToken(token: Token): IElementType {
            return when (token.type) {
                TokenTypes.COLON -> MipsElementTypes.COLON
                TokenTypes.COMMENT -> MipsTokenTypes.COMMENT
                TokenTypes.DELIMITER -> TokenType.WHITE_SPACE
                TokenTypes.DIRECTIVE -> MipsElementTypes.DIRECTIVE
                TokenTypes.ERROR -> TokenType.BAD_CHARACTER
                TokenTypes.IDENTIFIER -> MipsElementTypes.IDENTIFIER
                TokenTypes.INTEGER_5 -> MipsElementTypes.INTEGER_5
                TokenTypes.INTEGER_16 -> MipsElementTypes.INTEGER_16
                TokenTypes.INTEGER_16U -> MipsElementTypes.INTEGER_16U
                TokenTypes.INTEGER_32 -> MipsElementTypes.INTEGER_32
                TokenTypes.LEFT_PAREN -> MipsElementTypes.LPAREN
                TokenTypes.MINUS -> MipsElementTypes.MINUS
                TokenTypes.OPERATOR -> MipsElementTypes.OPERATOR
                TokenTypes.PLUS -> MipsElementTypes.PLUS
                TokenTypes.QUOTED_STRING -> MipsElementTypes.QUOTED_STRING
                TokenTypes.REAL_NUMBER -> MipsElementTypes.REAL_NUMBER
                TokenTypes.REGISTER_NAME -> MipsElementTypes.REGISTER_NAME
                TokenTypes.REGISTER_NUMBER -> MipsElementTypes.REGISTER_NUMBER
                TokenTypes.RIGHT_PAREN -> MipsElementTypes.RPAREN
                // TODO: Add support for these two token types
                TokenTypes.FP_REGISTER_NAME, TokenTypes.MACRO_PARAMETER ->
                    throw MipsException("Unknown token type: ${token.type}")
            }
        }
    }
}