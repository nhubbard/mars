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

// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.psi;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import edu.missouristate.mars.earth.lang.psi.impl.*;

public interface MipsElementTypes {

    IElementType DIRECTIVE_ARG = new MipsElementType("DIRECTIVE_ARG");
    IElementType DIRECTIVE_STATEMENT = new MipsElementType("DIRECTIVE_STATEMENT");
    IElementType INSTRUCTION = new MipsElementType("INSTRUCTION");
    IElementType INSTRUCTION_ARG = new MipsElementType("INSTRUCTION_ARG");
    IElementType LABEL_DEFINITION = new MipsElementType("LABEL_DEFINITION");
    IElementType LABEL_IDENTIFIER = new MipsElementType("LABEL_IDENTIFIER");
    IElementType NUMBER_LITERAL = new MipsElementType("NUMBER_LITERAL");
    IElementType NUMBER_RANGE = new MipsElementType("NUMBER_RANGE");
    IElementType REGISTER_LITERAL = new MipsElementType("REGISTER_LITERAL");
    IElementType REGISTER_OFFSET = new MipsElementType("REGISTER_OFFSET");
    IElementType STRING_LITERAL = new MipsElementType("STRING_LITERAL");

    IElementType COLON = new MipsTokenType("COLON");
    IElementType COMMA = new MipsTokenType("COMMA");
    IElementType DIRECTIVE = new MipsTokenType("DIRECTIVE");
    IElementType EOL = new MipsTokenType("EOL");
    IElementType FP_REGISTER_NAME = new MipsTokenType("FP_REGISTER_NAME");
    IElementType IDENTIFIER = new MipsTokenType("IDENTIFIER");
    IElementType INTEGER_16 = new MipsTokenType("INTEGER_16");
    IElementType INTEGER_16U = new MipsTokenType("INTEGER_16U");
    IElementType INTEGER_32 = new MipsTokenType("INTEGER_32");
    IElementType INTEGER_5 = new MipsTokenType("INTEGER_5");
    IElementType LPAREN = new MipsTokenType("LPAREN");
    IElementType LQUOTE = new MipsTokenType("LQUOTE");
    IElementType MINUS = new MipsTokenType("MINUS");
    IElementType OPERATOR = new MipsTokenType("OPERATOR");
    IElementType PLUS = new MipsTokenType("PLUS");
    IElementType QUOTED_STRING = new MipsTokenType("QUOTED_STRING");
    IElementType REAL_NUMBER = new MipsTokenType("REAL_NUMBER");
    IElementType REGISTER_NAME = new MipsTokenType("REGISTER_NAME");
    IElementType REGISTER_NUMBER = new MipsTokenType("REGISTER_NUMBER");
    IElementType RPAREN = new MipsTokenType("RPAREN");
    IElementType RQUOTE = new MipsTokenType("RQUOTE");

    class Factory {
        public static PsiElement createElement(ASTNode node) {
            IElementType type = node.getElementType();
            if (type == DIRECTIVE_ARG) {
                return new MipsDirectiveArgImpl(node);
            } else if (type == DIRECTIVE_STATEMENT) {
                return new MipsDirectiveStatementImpl(node);
            } else if (type == INSTRUCTION) {
                return new MipsInstructionImpl(node);
            } else if (type == INSTRUCTION_ARG) {
                return new MipsInstructionArgImpl(node);
            } else if (type == LABEL_DEFINITION) {
                return new MipsLabelDefinitionImpl(node);
            } else if (type == LABEL_IDENTIFIER) {
                return new MipsLabelIdentifierImpl(node);
            } else if (type == NUMBER_LITERAL) {
                return new MipsNumberLiteralImpl(node);
            } else if (type == NUMBER_RANGE) {
                return new MipsNumberRangeImpl(node);
            } else if (type == REGISTER_LITERAL) {
                return new MipsRegisterLiteralImpl(node);
            } else if (type == REGISTER_OFFSET) {
                return new MipsRegisterOffsetImpl(node);
            } else if (type == STRING_LITERAL) {
                return new MipsStringLiteralImpl(node);
            }
            throw new AssertionError("Unknown element type: " + type);
        }
    }
}
