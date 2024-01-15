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

package edu.missouristate.mars.venus.editors.tokenmarker;

/**
 * A linked list of tokens. Each token has three fields - a token
 * identifier, which is a byte value that can be looked up in the
 * array returned by <code>SyntaxDocument.getColors()</code>
 * to get a color value, a length value which is the length of the
 * token in the text, and a pointer to the next token in the list.
 *
 * @author Slava Pestov
 * @version $Id: Token.java,v 1.12 1999/12/13 03:40:30 sp Exp $
 */

public class Token {
    // NOTE from DPS 13-May-2010.
    // Please do not modify any of these constants!  It's not fatal or
    // anything, but will cause funny results in the MARS Settings
    // mechanism (at least temporarily until changed).  The
    // associated values here are appended into the key names for
    // persistent storage (e.g. registry) of syntax style information
    // for the various tokens.

    /**
     * Normal text token id. This should be used to mark
     * normal text.
     */
    public static final byte NULL = 0;

    /**
     * Comment 1 token id. This can be used to mark a comment.
     */
    public static final byte COMMENT1 = 1;

    /**
     * Comment 2 token id. This can be used to mark a comment.
     */
    public static final byte COMMENT2 = 2;


    /**
     * Literal 1 token id. This can be used to mark a string
     * literal (eg, C mode uses this to mark "..." literals)
     */
    public static final byte LITERAL1 = 3;

    /**
     * Literal 2 token id. This can be used to mark an object
     * literal (eg, Java mode uses this to mark true, false, etc)
     */
    public static final byte LITERAL2 = 4;

    /**
     * Label token id. This can be used to mark labels
     * (eg, C mode uses this to mark ...: sequences)
     */
    public static final byte LABEL = 5;

    /**
     * Keyword 1 token id. This can be used to mark a
     * keyword. This should be used for general language
     * constructs.
     */
    public static final byte KEYWORD1 = 6;

    /**
     * Keyword 2 token id. This can be used to mark a
     * keyword. This should be used for preprocessor
     * commands, or variables.
     */
    public static final byte KEYWORD2 = 7;

    /**
     * Keyword 3 token id. This can be used to mark a
     * keyword. This should be used for data types.
     */
    public static final byte KEYWORD3 = 8;

    /**
     * Operator token id. This can be used to mark an
     * operator. (eg, SQL mode marks +, -, etc with this
     * token type)
     */
    public static final byte OPERATOR = 9;

    /**
     * Invalid token id. This can be used to mark invalid
     * or incomplete tokens, so the user can easily spot
     * syntax errors.
     */
    public static final byte INVALID = 10;

    /**
     * Macro parameter token.  Added for MARS 4.3.
     */
    public static final byte MACRO_ARG = 11;

    /**
     * The total number of defined token ids.
     */
    public static final byte ID_COUNT = 12;

    /**
     * The first id that can be used for internal state
     * in a token marker.
     */
    public static final byte INTERNAL_FIRST = 100;

    /**
     * The last id that can be used for internal state
     * in a token marker.
     */
    public static final byte INTERNAL_LAST = 126;

    /**
     * The token type, that along with a length of 0
     * marks the end of the token list.
     */
    public static final byte END = 127;

    /**
     * The length of this token.
     */
    public int length;

    /**
     * The id of this token.
     */
    public byte id;

    /**
     * The next token in the linked list.
     */
    public Token next;

    /**
     * Creates a new token.
     *
     * @param length The length of the token
     * @param id     The id of the token
     */
    public Token(int length, byte id) {
        this.length = length;
        this.id = id;
    }

    /**
     * Returns a string representation of this token.
     */
    public String toString() {
        return "[id=" + id + ",length=" + length + "]";
    }
}
