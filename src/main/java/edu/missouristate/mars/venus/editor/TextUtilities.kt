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

package edu.missouristate.mars.venus.editor;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;

/**
 * Class with several utility functions used by the text area component.
 *
 * @author Slava Pestov
 * @version $Id: TextUtilities.java,v 1.4 1999/12/13 03:40:30 sp Exp $
 */
public class TextUtilities {
    /**
     * Returns the offset of the bracket matching the one at the
     * specified offset of the document, or -1 if the bracket is
     * unmatched (or if the character is not a bracket).
     *
     * @param doc    The document
     * @param offset The offset
     * @throws BadLocationException If an out-of-bounds access
     *                              was attempted on the document text
     */
    public static int findMatchingBracket(Document doc, int offset)
            throws BadLocationException {
        if (doc.getLength() == 0)
            return -1;
        char c = doc.getText(offset, 1).charAt(0);
        char cprime; // c` - corresponding character
        boolean direction; // true = back, false = forward

        switch (c) {
            case '(':
                cprime = ')';
                direction = false;
                break;
            case ')':
                cprime = '(';
                direction = true;
                break;
            case '[':
                cprime = ']';
                direction = false;
                break;
            case ']':
                cprime = '[';
                direction = true;
                break;
            case '{':
                cprime = '}';
                direction = false;
                break;
            case '}':
                cprime = '{';
                direction = true;
                break;
            default:
                return -1;
        }

        int count;

        // How to merge these two cases is left as an exercise
        // for the reader.

        // Go back or forward
        if (direction) {
            // Count is 1 initially because we have already
            // `found' one closing bracket
            count = 1;

            // Get text[0,offset-1];
            String text = doc.getText(0, offset);

            // Scan backwards
            for (int i = offset - 1; i >= 0; i--) {
                // If text[i] == c, we have found another
                // closing bracket, therefore we will need
                // two opening brackets to complete the
                // match.
                char x = text.charAt(i);
                if (x == c)
                    count++;

                    // If text[i] == cprime, we have found a
                    // opening bracket, so we return i if
                    // --count == 0
                else if (x == cprime) {
                    if (--count == 0)
                        return i;
                }
            }
        } else {
            // Count is 1 initially because we have already
            // `found' one opening bracket
            count = 1;

            // So we don't have to + 1 in every loop
            offset++;

            // Number of characters to check
            int len = doc.getLength() - offset;

            // Get text[offset+1,len];
            String text = doc.getText(offset, len);

            // Scan forwards
            for (int i = 0; i < len; i++) {
                // If text[i] == c, we have found another
                // opening bracket, therefore we will need
                // two closing brackets to complete the
                // match.
                char x = text.charAt(i);

                if (x == c)
                    count++;

                    // If text[i] == cprime, we have found an
                    // closing bracket, so we return i if
                    // --count == 0
                else if (x == cprime) {
                    if (--count == 0)
                        return i + offset;
                }
            }
        }

        // Nothing found
        return -1;
    }

    /**
     * Locates the start of the word at the specified position.
     *
     * @param line The text
     * @param pos  The position
     */
    public static int findWordStart(String line, int pos, String noWordSep) {
        char ch = line.charAt(pos - 1);

        if (noWordSep == null)
            noWordSep = "";
        boolean selectNoLetter = (!Character.isLetterOrDigit(ch)
                && noWordSep.indexOf(ch) == -1);

        int wordStart = 0;
        for (int i = pos - 1; i >= 0; i--) {
            ch = line.charAt(i);
            if (selectNoLetter ^ (!Character.isLetterOrDigit(ch) &&
                    noWordSep.indexOf(ch) == -1)) {
                wordStart = i + 1;
                break;
            }
        }

        return wordStart;
    }

    /**
     * Locates the end of the word at the specified position.
     *
     * @param line The text
     * @param pos  The position
     */
    public static int findWordEnd(String line, int pos, String noWordSep) {
        char ch = line.charAt(pos);

        if (noWordSep == null)
            noWordSep = "";
        boolean selectNoLetter = (!Character.isLetterOrDigit(ch)
                && noWordSep.indexOf(ch) == -1);

        int wordEnd = line.length();
        for (int i = pos; i < line.length(); i++) {
            ch = line.charAt(i);
            if (selectNoLetter ^ (!Character.isLetterOrDigit(ch) &&
                    noWordSep.indexOf(ch) == -1)) {
                wordEnd = i;
                break;
            }
        }
        return wordEnd;
    }
}
