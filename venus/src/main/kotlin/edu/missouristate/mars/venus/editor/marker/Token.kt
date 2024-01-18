/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 */

package edu.missouristate.mars.venus.editor.marker

data class Token @JvmOverloads constructor(
    var length: Int,
    var type: Type,
    var next: Token? = null
) {
    enum class Type(@JvmField val rawValue: Byte) {
        /** Normal text token ID. Used to mark normal text. */
        NULL(0),
        /** Used to mark a comment. */
        COMMENT1(1),
        /** Used to mark a comment, but different from [COMMENT1]? */
        COMMENT2(2),
        /** Used to mark a string literal. */
        LITERAL1(3),
        /** Used to mark an object literal. */
        LITERAL2(4),
        /** Used to mark labels. */
        LABEL(5),
        /** Used to mark keywords. */
        KEYWORD1(6),
        /** Used to mark *different* keywords. */
        KEYWORD2(7),
        /** Used to mark even *more* different keywords. */
        KEYWORD3(8),
        /** Used to mark operator. */
        OPERATOR(9),
        /** Used to mark invalid or incomplete tokens. */
        INVALID(10),
        /** Used to mark macro parameter. */
        MACRO_ARG(11),

        // Internal states are types used for allowing custom tokens.

        INTERNAL_1(100),
        INTERNAL_2(101),
        INTERNAL_3(102),
        INTERNAL_4(103),
        INTERNAL_5(104),
        INTERNAL_6(105),
        INTERNAL_7(106),
        INTERNAL_8(107),
        INTERNAL_9(108),
        INTERNAL_10(109),
        INTERNAL_11(110),
        INTERNAL_12(111),
        INTERNAL_13(112),
        INTERNAL_14(113),
        INTERNAL_15(114),
        INTERNAL_16(115),
        INTERNAL_17(116),
        INTERNAL_18(117),
        INTERNAL_19(118),
        INTERNAL_20(119),
        INTERNAL_21(120),
        INTERNAL_22(121),
        INTERNAL_23(122),
        INTERNAL_24(123),
        INTERNAL_25(124),
        INTERNAL_26(125),
        INTERNAL_27(126),

        /** The token type, which must have a length of zero, that marks the end of the token list. */
        END(127);

        companion object {
            @JvmStatic
            fun fromInt(rawValue: Byte) = entries.firstOrNull { it.rawValue == rawValue } ?: NULL
        }

        val isInternal: Boolean
            get() = rawValue in 100..126
        val isKeyword: Boolean
            get() = rawValue in 6..8
    }

    override fun toString(): String = "[id=$type,length=$length]"
}