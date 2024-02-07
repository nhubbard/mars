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

// Generated by JFlex 1.9.1 http://jflex.de/  (tweaked for IntelliJ platform)
// source: Mips.flex

package edu.missouristate.mars.earth.lang.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import edu.missouristate.mars.earth.MarsUtils;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static edu.missouristate.mars.earth.lang.psi.MipsElementTypes.*;


public class MipsLexer implements FlexLexer {

    /**
     * This character denotes the end of file
     */
    public static final int YYEOF = -1;

    /**
     * initial size of the lookahead buffer
     */
    private static final int ZZ_BUFFERSIZE = 16384;

    /**
     * lexical states
     */
    public static final int YYINITIAL = 0;
    public static final int IN_QUOTES = 2;

    /**
     * ZZ_LEXSTATE[l] is the state in the DFA for the lexical state l
     * ZZ_LEXSTATE[l+1] is the state in the DFA for the lexical state l
     * at the beginning of a line
     * l is of the form l = 2*k, k a non negative integer
     */
    private static final int ZZ_LEXSTATE[] = {
            0, 0, 1, 1
    };

    /**
     * Top-level table for translating characters to character classes
     */
    private static final int[] ZZ_CMAP_TOP = zzUnpackcmap_top();

    private static final String ZZ_CMAP_TOP_PACKED_0 =
            "\1\0\5\u0100\1\u0200\1\u0300\1\u0100\5\u0400\1\u0500\1\u0600" +
                    "\1\u0700\6\u0100\1\u0800\1\u0900\1\u0a00\1\u0b00\1\u0c00\1\u0d00" +
                    "\3\u0100\1\u0e00\205\u0100\1\u0600\1\u0100\1\u0f00\1\u1000\1\u1100" +
                    "\1\u1200\54\u0100\10\u1300\37\u0100\1\u0900\4\u0100\1\u1400\10\u0100" +
                    "\1\u1500\2\u0100\1\u1600\1\u1700\1\u1200\1\u0100\1\u0500\1\u0100" +
                    "\1\u1800\1\u1500\1\u0800\3\u0100\1\u1100\1\u1900\114\u0100\1\u1a00" +
                    "\1\u1100\153\u0100\1\u1b00\11\u0100\1\u1c00\1\u1200\6\u0100\1\u1100" +
                    "\u0f16\u0100";

    private static int[] zzUnpackcmap_top() {
        int[] result = new int[4352];
        int offset = 0;
        offset = zzUnpackcmap_top(ZZ_CMAP_TOP_PACKED_0, offset, result);
        return result;
    }

    private static int zzUnpackcmap_top(String packed, int offset, int[] result) {
        int i = 0;       /* index in packed string  */
        int j = offset;  /* index in unpacked array */
        int l = packed.length();
        while (i < l) {
            int count = packed.charAt(i++);
            int value = packed.charAt(i++);
            do result[j++] = value; while (--count > 0);
        }
        return j;
    }


    /**
     * Second-level tables for translating characters to character classes
     */
    private static final int[] ZZ_CMAP_BLOCKS = zzUnpackcmap_blocks();

    private static final String ZZ_CMAP_BLOCKS_PACKED_0 =
            "\11\0\1\1\1\2\2\3\1\4\22\0\1\5\1\0" +
                    "\1\6\1\7\1\10\1\11\1\0\1\11\1\12\1\13" +
                    "\1\0\1\14\1\15\1\16\1\17\1\0\12\20\1\21" +
                    "\2\0\1\11\3\0\32\22\1\11\1\23\1\11\1\0" +
                    "\1\24\1\11\1\22\1\25\1\22\2\25\1\26\7\22" +
                    "\1\25\3\22\3\25\1\22\1\25\4\22\1\11\1\0" +
                    "\1\11\7\0\1\3\u01da\0\12\27\206\0\12\27\306\0" +
                    "\12\27\234\0\12\27\166\0\12\27\140\0\12\27\166\0" +
                    "\12\27\106\0\12\27\u0116\0\12\27\106\0\12\27\u0146\0" +
                    "\12\27\46\0\12\27\u012c\0\12\27\200\0\12\27\246\0" +
                    "\12\27\6\0\12\27\266\0\12\27\126\0\12\27\206\0" +
                    "\12\27\6\0\12\27\316\0\2\3\u01a6\0\12\27\46\0" +
                    "\12\27\306\0\12\27\26\0\12\27\126\0\12\27\u0196\0" +
                    "\12\27\6\0\u0100\30\240\0\12\27\206\0\12\27\u012c\0" +
                    "\12\27\200\0\12\27\74\0\12\27\220\0\12\27\166\0" +
                    "\12\27\146\0\12\27\206\0\12\27\106\0\12\27\266\0" +
                    "\12\27\u0164\0\62\27\100\0\12\27\266\0";

    private static int[] zzUnpackcmap_blocks() {
        int[] result = new int[7424];
        int offset = 0;
        offset = zzUnpackcmap_blocks(ZZ_CMAP_BLOCKS_PACKED_0, offset, result);
        return result;
    }

    private static int zzUnpackcmap_blocks(String packed, int offset, int[] result) {
        int i = 0;       /* index in packed string  */
        int j = offset;  /* index in unpacked array */
        int l = packed.length();
        while (i < l) {
            int count = packed.charAt(i++);
            int value = packed.charAt(i++);
            do result[j++] = value; while (--count > 0);
        }
        return j;
    }

    /**
     * Translates DFA states to action switch labels.
     */
    private static final int[] ZZ_ACTION = zzUnpackAction();

    private static final String ZZ_ACTION_PACKED_0 =
            "\2\0\1\1\1\2\2\3\1\4\1\5\1\6\1\7" +
                    "\1\10\1\11\1\12\1\13\1\14\2\15\1\16\1\15" +
                    "\1\1\1\17\1\20\1\1\1\7\1\21\1\22\1\0" +
                    "\1\15\1\0\1\21\2\22";

    private static int[] zzUnpackAction() {
        int[] result = new int[32];
        int offset = 0;
        offset = zzUnpackAction(ZZ_ACTION_PACKED_0, offset, result);
        return result;
    }

    private static int zzUnpackAction(String packed, int offset, int[] result) {
        int i = 0;       /* index in packed string  */
        int j = offset;  /* index in unpacked array */
        int l = packed.length();
        while (i < l) {
            int count = packed.charAt(i++);
            int value = packed.charAt(i++);
            do result[j++] = value; while (--count > 0);
        }
        return j;
    }


    /**
     * Translates a state to a row index in the transition table
     */
    private static final int[] ZZ_ROWMAP = zzUnpackRowMap();

    private static final String ZZ_ROWMAP_PACKED_0 =
            "\0\0\0\31\0\62\0\62\0\62\0\113\0\62\0\62" +
                    "\0\144\0\175\0\62\0\62\0\62\0\62\0\226\0\257" +
                    "\0\226\0\62\0\310\0\341\0\372\0\62\0\u0113\0\u012c" +
                    "\0\u0145\0\u015e\0\341\0\u0177\0\u0113\0\u0190\0\u01a9\0\u01c2";

    private static int[] zzUnpackRowMap() {
        int[] result = new int[32];
        int offset = 0;
        offset = zzUnpackRowMap(ZZ_ROWMAP_PACKED_0, offset, result);
        return result;
    }

    private static int zzUnpackRowMap(String packed, int offset, int[] result) {
        int i = 0;  /* index in packed string  */
        int j = offset;  /* index in unpacked array */
        int l = packed.length() - 1;
        while (i < l) {
            int high = packed.charAt(i++) << 16;
            result[j++] = high | packed.charAt(i++);
        }
        return j;
    }

    /**
     * The transition table of the DFA
     */
    private static final int[] ZZ_TRANS = zzUnpacktrans();

    private static final String ZZ_TRANS_PACKED_0 =
            "\1\3\1\4\2\5\1\6\1\7\1\10\1\11\1\12" +
                    "\1\3\1\13\1\14\1\15\1\16\1\17\1\20\1\21" +
                    "\1\22\1\23\1\3\3\23\1\24\1\3\6\25\1\26" +
                    "\14\25\1\27\5\25\33\0\1\5\26\0\2\11\3\0" +
                    "\23\11\21\0\1\30\1\0\1\31\2\0\1\31\1\32" +
                    "\1\30\17\0\1\23\1\20\1\21\1\0\1\23\1\0" +
                    "\3\23\1\33\17\0\2\23\1\20\1\0\1\23\1\0" +
                    "\3\23\1\34\17\0\3\23\1\0\1\23\1\0\3\23" +
                    "\21\0\1\34\1\33\6\0\1\33\1\0\6\25\1\0" +
                    "\14\25\1\35\5\25\6\0\1\25\1\0\2\25\3\0" +
                    "\1\25\1\0\1\25\3\0\4\25\22\0\1\30\6\0" +
                    "\1\30\21\0\1\36\1\0\1\31\2\0\2\31\1\36" +
                    "\21\0\1\37\1\0\1\32\2\0\2\32\1\36\21\0" +
                    "\1\34\6\0\1\34\21\0\1\36\6\0\1\36\21\0" +
                    "\1\37\1\0\1\40\2\0\2\40\1\36\21\0\1\40" +
                    "\1\0\1\40\2\0\2\40\2\0";

    private static int[] zzUnpacktrans() {
        int[] result = new int[475];
        int offset = 0;
        offset = zzUnpacktrans(ZZ_TRANS_PACKED_0, offset, result);
        return result;
    }

    private static int zzUnpacktrans(String packed, int offset, int[] result) {
        int i = 0;       /* index in packed string  */
        int j = offset;  /* index in unpacked array */
        int l = packed.length();
        while (i < l) {
            int count = packed.charAt(i++);
            int value = packed.charAt(i++);
            value--;
            do result[j++] = value; while (--count > 0);
        }
        return j;
    }


    /* error codes */
    private static final int ZZ_UNKNOWN_ERROR = 0;
    private static final int ZZ_NO_MATCH = 1;
    private static final int ZZ_PUSHBACK_2BIG = 2;

    /* error messages for the codes above */
    private static final String[] ZZ_ERROR_MSG = {
            "Unknown internal scanner error",
            "Error: could not match input",
            "Error: pushback value was too large"
    };

    /**
     * ZZ_ATTRIBUTE[aState] contains the attributes of state {@code aState}
     */
    private static final int[] ZZ_ATTRIBUTE = zzUnpackAttribute();

    private static final String ZZ_ATTRIBUTE_PACKED_0 =
            "\2\0\3\11\1\1\2\11\2\1\4\11\3\1\1\11" +
                    "\3\1\1\11\4\1\1\0\1\1\1\0\3\1";

    private static int[] zzUnpackAttribute() {
        int[] result = new int[32];
        int offset = 0;
        offset = zzUnpackAttribute(ZZ_ATTRIBUTE_PACKED_0, offset, result);
        return result;
    }

    private static int zzUnpackAttribute(String packed, int offset, int[] result) {
        int i = 0;       /* index in packed string  */
        int j = offset;  /* index in unpacked array */
        int l = packed.length();
        while (i < l) {
            int count = packed.charAt(i++);
            int value = packed.charAt(i++);
            do result[j++] = value; while (--count > 0);
        }
        return j;
    }

    /**
     * the input device
     */
    private java.io.Reader zzReader;

    /**
     * the current state of the DFA
     */
    private int zzState;

    /**
     * the current lexical state
     */
    private int zzLexicalState = YYINITIAL;

    /**
     * this buffer contains the current text to be matched and is
     * the source of the yytext() string
     */
    private CharSequence zzBuffer = "";

    /**
     * the textposition at the last accepting state
     */
    private int zzMarkedPos;

    /**
     * the current text position in the buffer
     */
    private int zzCurrentPos;

    /**
     * startRead marks the beginning of the yytext() string in the buffer
     */
    private int zzStartRead;

    /**
     * endRead marks the last character in the buffer, that has been read
     * from input
     */
    private int zzEndRead;

    /**
     * zzAtEOF == true <=> the scanner is at the EOF
     */
    private boolean zzAtEOF;

    /**
     * Number of newlines encountered up to the start of the matched text.
     */
    @SuppressWarnings("unused")
    private int yyline;

    /**
     * Number of characters from the last newline up to the start of the matched text.
     */
    @SuppressWarnings("unused")
    protected int yycolumn;

    /**
     * Number of characters up to the start of the matched text.
     */
    @SuppressWarnings("unused")
    private long yychar;

    /**
     * Whether the scanner is currently at the beginning of a line.
     */
    @SuppressWarnings("unused")
    private boolean zzAtBOL = true;

    /**
     * Whether the user-EOF-code has already been executed.
     */
    @SuppressWarnings("unused")
    private boolean zzEOFDone;

    /* user code: */
    public MipsLexer() {
        this((java.io.Reader) null);
    }


    /**
     * Creates a new scanner
     *
     * @param in the java.io.Reader to read input from.
     */
    public MipsLexer(java.io.Reader in) {
        this.zzReader = in;
    }


    /**
     * Returns the maximum size of the scanner buffer, which limits the size of tokens.
     */
    private int zzMaxBufferLen() {
        return Integer.MAX_VALUE;
    }

    /**
     * Whether the scanner buffer can grow to accommodate a larger token.
     */
    private boolean zzCanGrow() {
        return true;
    }

    /**
     * Translates raw input code points to DFA table row
     */
    private static int zzCMap(int input) {
        int offset = input & 255;
        return offset == input ? ZZ_CMAP_BLOCKS[offset] : ZZ_CMAP_BLOCKS[ZZ_CMAP_TOP[input >> 8] | offset];
    }

    public final int getTokenStart() {
        return zzStartRead;
    }

    public final int getTokenEnd() {
        return getTokenStart() + yylength();
    }

    public void reset(CharSequence buffer, int start, int end, int initialState) {
        zzBuffer = buffer;
        zzCurrentPos = zzMarkedPos = zzStartRead = start;
        zzAtEOF = false;
        zzAtBOL = true;
        zzEndRead = end;
        yybegin(initialState);
    }

    /**
     * Refills the input buffer.
     *
     * @return {@code false}, iff there was new input.
     * @throws java.io.IOException if any I/O-Error occurs
     */
    private boolean zzRefill() throws java.io.IOException {
        return true;
    }


    /**
     * Returns the current lexical state.
     */
    public final int yystate() {
        return zzLexicalState;
    }


    /**
     * Enters a new lexical state
     *
     * @param newState the new lexical state
     */
    public final void yybegin(int newState) {
        zzLexicalState = newState;
    }


    /**
     * Returns the text matched by the current regular expression.
     */
    public final CharSequence yytext() {
        return zzBuffer.subSequence(zzStartRead, zzMarkedPos);
    }


    /**
     * Returns the character at position {@code pos} from the
     * matched text.
     * <p>
     * It is equivalent to yytext().charAt(pos), but faster
     *
     * @param pos the position of the character to fetch.
     *            A value from 0 to yylength()-1.
     * @return the character at position pos
     */
    public final char yycharat(int pos) {
        return zzBuffer.charAt(zzStartRead + pos);
    }


    /**
     * Returns the length of the matched text region.
     */
    public final int yylength() {
        return zzMarkedPos - zzStartRead;
    }


    /**
     * Reports an error that occurred while scanning.
     * <p>
     * In a wellformed scanner (no or only correct usage of
     * yypushback(int) and a match-all fallback rule) this method
     * will only be called with things that "Can't Possibly Happen".
     * If this method is called, something is seriously wrong
     * (e.g. a JFlex bug producing a faulty scanner etc.).
     * <p>
     * Usual syntax/scanner level error handling should be done
     * in error fallback rules.
     *
     * @param errorCode the code of the errormessage to display
     */
    private void zzScanError(int errorCode) {
        String message;
        try {
            message = ZZ_ERROR_MSG[errorCode];
        } catch (ArrayIndexOutOfBoundsException e) {
            message = ZZ_ERROR_MSG[ZZ_UNKNOWN_ERROR];
        }

        throw new Error(message);
    }


    /**
     * Pushes the specified amount of characters back into the input stream.
     * <p>
     * They will be read again by then next call of the scanning method
     *
     * @param number the number of characters to be read again.
     *               This number must not be greater than yylength()!
     */
    public void yypushback(int number) {
        if (number > yylength())
            zzScanError(ZZ_PUSHBACK_2BIG);

        zzMarkedPos -= number;
    }


    /**
     * Resumes scanning until the next regular expression is matched,
     * the end of input is encountered or an I/O-Error occurs.
     *
     * @return the next token
     * @throws java.io.IOException if any I/O-Error occurs
     */
    public IElementType advance() throws java.io.IOException {
        int zzInput;
        int zzAction;

        // cached fields:
        int zzCurrentPosL;
        int zzMarkedPosL;
        int zzEndReadL = zzEndRead;
        CharSequence zzBufferL = zzBuffer;

        int[] zzTransL = ZZ_TRANS;
        int[] zzRowMapL = ZZ_ROWMAP;
        int[] zzAttrL = ZZ_ATTRIBUTE;

        while (true) {
            zzMarkedPosL = zzMarkedPos;

            zzAction = -1;

            zzCurrentPosL = zzCurrentPos = zzStartRead = zzMarkedPosL;

            zzState = ZZ_LEXSTATE[zzLexicalState];

            // set up zzAction for empty match case:
            int zzAttributes = zzAttrL[zzState];
            if ((zzAttributes & 1) == 1) {
                zzAction = zzState;
            }


            zzForAction:
            {
                while (true) {

                    if (zzCurrentPosL < zzEndReadL) {
                        zzInput = Character.codePointAt(zzBufferL, zzCurrentPosL);
                        zzCurrentPosL += Character.charCount(zzInput);
                    } else if (zzAtEOF) {
                        zzInput = YYEOF;
                        break zzForAction;
                    } else {
                        // store back cached positions
                        zzCurrentPos = zzCurrentPosL;
                        zzMarkedPos = zzMarkedPosL;
                        boolean eof = zzRefill();
                        // get translated positions and possibly new buffer
                        zzCurrentPosL = zzCurrentPos;
                        zzMarkedPosL = zzMarkedPos;
                        zzBufferL = zzBuffer;
                        zzEndReadL = zzEndRead;
                        if (eof) {
                            zzInput = YYEOF;
                            break zzForAction;
                        } else {
                            zzInput = Character.codePointAt(zzBufferL, zzCurrentPosL);
                            zzCurrentPosL += Character.charCount(zzInput);
                        }
                    }
                    int zzNext = zzTransL[zzRowMapL[zzState] + zzCMap(zzInput)];
                    if (zzNext == -1) break zzForAction;
                    zzState = zzNext;

                    zzAttributes = zzAttrL[zzState];
                    if ((zzAttributes & 1) == 1) {
                        zzAction = zzState;
                        zzMarkedPosL = zzCurrentPosL;
                        if ((zzAttributes & 8) == 8) break zzForAction;
                    }

                }
            }

            // store back cached position
            zzMarkedPos = zzMarkedPosL;

            if (zzInput == YYEOF && zzStartRead == zzCurrentPos) {
                zzAtEOF = true;
                return null;
            } else {
                switch (zzAction < 0 ? zzAction : ZZ_ACTION[zzAction]) {
                    case 1: {
                        return BAD_CHARACTER;
                    }
                    // fall through
                    case 19:
                        break;
                    case 2: {
                        return edu.missouristate.mars.earth.lang.psi.MipsTokenTypes.TAB;
                    }
                    // fall through
                    case 20:
                        break;
                    case 3: {
                        return EOL;
                    }
                    // fall through
                    case 21:
                        break;
                    case 4: {
                        return WHITE_SPACE;
                    }
                    // fall through
                    case 22:
                        break;
                    case 5: {
                        yybegin(IN_QUOTES);
                        return LQUOTE;
                    }
                    // fall through
                    case 23:
                        break;
                    case 6: {
                        return edu.missouristate.mars.earth.lang.psi.MipsTokenTypes.COMMENT;
                    }
                    // fall through
                    case 24:
                        break;
                    case 7: {
                        return REGISTER_NUMBER;
                    }
                    // fall through
                    case 25:
                        break;
                    case 8: {
                        return LPAREN;
                    }
                    // fall through
                    case 26:
                        break;
                    case 9: {
                        return RPAREN;
                    }
                    // fall through
                    case 27:
                        break;
                    case 10: {
                        return PLUS;
                    }
                    // fall through
                    case 28:
                        break;
                    case 11: {
                        return COMMA;
                    }
                    // fall through
                    case 29:
                        break;
                    case 12: {
                        return MINUS;
                    }
                    // fall through
                    case 30:
                        break;
                    case 13: {
                        return MarsUtils.getTokenType(yytext());
                    }
                    // fall through
                    case 31:
                        break;
                    case 14: {
                        return COLON;
                    }
                    // fall through
                    case 32:
                        break;
                    case 15: {
                        return QUOTED_STRING;
                    }
                    // fall through
                    case 33:
                        break;
                    case 16: {
                        yybegin(YYINITIAL);
                        return RQUOTE;
                    }
                    // fall through
                    case 34:
                        break;
                    case 17: {
                        return REGISTER_NAME;
                    }
                    // fall through
                    case 35:
                        break;
                    case 18: {
                        return FP_REGISTER_NAME;
                    }
                    // fall through
                    case 36:
                        break;
                    default:
                        zzScanError(ZZ_NO_MATCH);
                }
            }
        }
    }


}